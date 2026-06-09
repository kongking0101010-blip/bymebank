package com.khmerbank.service.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends branded HTML emails.
 *
 * <p><b>Why two transports?</b> Render (and most cloud hosts running on AWS)
 * block outbound SMTP ports 25/465/587 at the infrastructure level, so
 * JavaMail/Gmail SMTP hangs forever in production. When {@code BREVO_API_KEY}
 * is set we send over Brevo's HTTPS transactional API (port 443, never
 * blocked). Locally — no Brevo key — we fall back to SMTP so dev still works.
 *
 * <p>Two flavors:
 * <ul>
 *   <li>{@link #sendOtp} — verification code</li>
 *   <li>{@link #sendKeyIssued} — sk_ key issued / rotated notification</li>
 * </ul>
 */
@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final ObjectMapper json = new ObjectMapper();
    private final WebClient brevo;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${app.mail.from-name:Byme Bank Payment Gateway}")
    private String fromName;

    @Value("${app.mail.dashboard-url:http://localhost:5173/app}")
    private String dashboardUrl;

    /** Brevo HTTPS API key. When blank we use SMTP (local dev). */
    @Value("${BREVO_API_KEY:${app.mail.brevo-key:}}")
    private String brevoApiKey;

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.brevo = WebClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .build();
    }

    /**
     * Fire-and-forget OTP send. Runs on the {@code mailExecutor} pool so the
     * login HTTP request returns instantly ("code sent") instead of blocking
     * while we talk to Brevo/SMTP. Delivery failures are logged, never thrown
     * — the user can hit "Resend" if a code doesn't arrive. This is what keeps
     * sign-in smooth and free of request timeouts.
     */
    @Async("mailExecutor")
    public void sendOtpAsync(String email, String code, int validMinutes) {
        try {
            sendOtp(email, code, validMinutes);
        } catch (Exception e) {
            log.error("async OTP send failed to {}: {}", mask(email), e.getMessage());
        }
    }

    /**
     * Send a 6-digit OTP. Throws if delivery fails. Prefer {@link #sendOtpAsync}
     * from request paths so the caller is never blocked on mail delivery.
     */
    public void sendOtp(String email, String code, int validMinutes) {
        Context ctx = new Context();
        ctx.setVariable("code", code);
        ctx.setVariable("minutes", validMinutes);
        ctx.setVariable("email", email);
        ctx.setVariable("year", Instant.now().toString().substring(0, 4));
        String html = templateEngine.process("email/otp", ctx);

        // Plain-text fallback significantly improves deliverability.
        String plain = String.format(
                "Your Byme Bank verification code: %s%n%n" +
                "This code expires in %d minutes.%n" +
                "If you didn't request this, ignore this email.%n%n" +
                "— Byme Bank Payment Gateway",
                code, validMinutes);

        send(email, "Byme Bank: " + code + " is your verification code", html, plain);
    }

    /**
     * Notify the user their sk_ key has been issued. Always sends the
     * masked key — never the full secret.
     */
    @Async("mailExecutor")
    public void sendKeyIssued(String email, Map<String, Object> vars) {
        Context ctx = new Context();
        vars.forEach(ctx::setVariable);
        ctx.setVariable("dashboardUrl", dashboardUrl);
        ctx.setVariable("year", Instant.now().toString().substring(0, 4));
        String html = templateEngine.process("email/key_issued", ctx);
        String plain = String.format(
                "Your Byme Bank API key is ready.%n%n" +
                "Plan: %s%n" +
                "Merchant: %s%n" +
                "Expires: %s%n" +
                "Masked key: %s%n%n" +
                "Open the dashboard to copy your key (shown once): %s%n",
                vars.get("plan"), vars.get("merchantName"),
                vars.get("expiresAt"), vars.get("maskedKey"),
                dashboardUrl);
        send(email, "Your Byme Bank API key is ready", html, plain);
    }

    private void send(String to, String subject, String html, String plain) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException(
                    "Mail not configured: set GMAIL_USERNAME (sender address) env var.");
        }
        if (brevoApiKey != null && !brevoApiKey.isBlank()) {
            sendViaBrevo(to, subject, html, plain);
        } else {
            sendViaSmtp(to, subject, html, plain);
        }
    }

    /** HTTPS transactional send — works on Render where SMTP is blocked. */
    private void sendViaBrevo(String to, String subject, String html, String plain) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, String> sender = new LinkedHashMap<>();
        sender.put("email", fromAddress);
        sender.put("name", fromName);
        body.put("sender", sender);
        body.put("to", List.of(Map.of("email", to)));
        body.put("subject", subject);
        body.put("htmlContent", html);
        if (plain != null && !plain.isBlank()) body.put("textContent", plain);
        try {
            String payload = json.writeValueAsString(body);
            brevo.post()
                    .uri("/smtp/email")
                    .header("api-key", brevoApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(20))
                    .block();
            log.info("mail sent (brevo) subject='{}' to={}", subject, mask(to));
        } catch (Exception e) {
            log.error("mail send failed (brevo) subject='{}' to={}: {}",
                    subject, mask(to), e.getMessage());
            throw new RuntimeException("Mail delivery failed: " + e.getMessage(), e);
        }
    }

    /** Classic SMTP — used only for local dev (Render blocks SMTP ports). */
    private void sendViaSmtp(String to, String subject, String html, String plain) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
            h.setReplyTo(fromAddress);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(plain == null ? stripHtml(html) : plain, html);
            msg.setHeader("X-Priority", "1");
            msg.setHeader("X-Auto-Response-Suppress", "All");
            msg.setHeader("Auto-Submitted", "auto-generated");
            msg.setHeader("List-Unsubscribe",
                    "<mailto:" + fromAddress + "?subject=unsubscribe>");
            mailSender.send(msg);
            log.info("mail sent (smtp) subject='{}' to={}", subject, mask(to));
        } catch (Exception e) {
            log.error("mail send failed (smtp) subject='{}' to={}: {}",
                    subject, mask(to), e.getMessage());
            throw new RuntimeException("Mail delivery failed: " + e.getMessage(), e);
        }
    }

    private static String stripHtml(String html) {
        return html == null ? "" : html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String mask(String email) {
        if (email == null) return "—";
        int at = email.indexOf('@');
        if (at < 1) return "***";
        String name = email.substring(0, at);
        String dom  = email.substring(at);
        return name.charAt(0) + "***" + dom;
    }
}
