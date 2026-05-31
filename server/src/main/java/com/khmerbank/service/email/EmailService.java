package com.khmerbank.service.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Sends branded HTML emails via Spring Mail (Gmail SMTP).
 *
 * <p>Two flavors:
 * <ul>
 *   <li>{@link #sendOtp} — verification code</li>
 *   <li>{@link #sendKeyIssued} — sk_ key issued / rotated notification</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${app.mail.from-name:KhmerBank Payment Gateway}")
    private String fromName;

    @Value("${app.mail.dashboard-url:http://localhost:5173/app}")
    private String dashboardUrl;

    /**
     * Send a 6-digit OTP. The code is included in the template so the user
     * can read it; never logged.
     */
    /**
     * Send a 6-digit OTP. Throws if Gmail isn't configured or Gmail rejects
     * — we want the request to fail so the user sees a clear error rather
     * than a 200 with a code that never arrives.
     */
    public void sendOtp(String email, String code, int validMinutes) {
        Context ctx = new Context();
        ctx.setVariable("code", code);
        ctx.setVariable("minutes", validMinutes);
        ctx.setVariable("email", email);
        ctx.setVariable("year", Instant.now().toString().substring(0, 4));
        String html = templateEngine.process("email/otp", ctx);

        // Plain-text fallback significantly improves Gmail deliverability.
        String plain = String.format(
                "Your KhmerBank verification code: %s%n%n" +
                "This code expires in %d minutes.%n" +
                "If you didn't request this, ignore this email.%n%n" +
                "— KhmerBank Payment Gateway",
                code, validMinutes);

        send(email, "KhmerBank: " + code + " is your verification code", html, plain);
    }

    /**
     * Notify the user their sk_ key has been issued. Always sends the
     * masked key — never the full secret.
     */
    @Async
    public void sendKeyIssued(String email, Map<String, Object> vars) {
        Context ctx = new Context();
        vars.forEach(ctx::setVariable);
        ctx.setVariable("dashboardUrl", dashboardUrl);
        ctx.setVariable("year", Instant.now().toString().substring(0, 4));
        String html = templateEngine.process("email/key_issued", ctx);
        String plain = String.format(
                "Your KhmerBank API key is ready.%n%n" +
                "Plan: %s%n" +
                "Merchant: %s%n" +
                "Expires: %s%n" +
                "Masked key: %s%n%n" +
                "Open the dashboard to copy your key (shown once): %s%n",
                vars.get("plan"), vars.get("merchantName"),
                vars.get("expiresAt"), vars.get("maskedKey"),
                dashboardUrl);
        send(email, "Your KhmerBank API key is ready", html, plain);
    }

    private void send(String to, String subject, String html, String plain) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException(
                    "Mail not configured: set GMAIL_USERNAME and GMAIL_APP_PASSWORD env vars.");
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            // multipart=true lets us attach a plain-text alternative.
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
            h.setReplyTo(fromAddress);
            h.setTo(to);
            h.setSubject(subject);
            // Order matters — plain first, HTML second, so clients render HTML
            // but spam filters see we're not HTML-only.
            h.setText(plain == null ? stripHtml(html) : plain, html);
            // Help Gmail tag this as a transactional notification.
            msg.setHeader("X-Priority", "1");
            msg.setHeader("X-Auto-Response-Suppress", "All");
            msg.setHeader("Auto-Submitted", "auto-generated");
            msg.setHeader("List-Unsubscribe",
                    "<mailto:" + fromAddress + "?subject=unsubscribe>");
            mailSender.send(msg);
            log.info("mail sent subject='{}' to={}", subject, mask(to));
        } catch (Exception e) {
            log.error("mail send failed subject='{}' to={}: {}",
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
