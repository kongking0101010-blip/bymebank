package com.khmerbank.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.khmerbank.model.AuditLog;
import com.khmerbank.model.User;
import com.khmerbank.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes structured audit-log rows. Every call is best-effort — a failure
 * here must not break the user's request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    public static final String LOGIN          = "LOGIN";
    public static final String LOGIN_FAILED   = "LOGIN_FAILED";
    public static final String OTP_REQUESTED  = "OTP_REQUESTED";
    public static final String OTP_VERIFIED   = "OTP_VERIFIED";
    public static final String OTP_BAD_CODE   = "OTP_BAD_CODE";
    public static final String OTP_LOCKED     = "OTP_LOCKED";
    public static final String GOOGLE_SIGNIN  = "GOOGLE_SIGNIN";
    public static final String MINT_KEY       = "MINT_KEY";
    public static final String REVOKE_KEY     = "REVOKE_KEY";
    public static final String RENEW_KEY      = "RENEW_KEY";
    public static final String GENERATE_QR    = "GENERATE_QR";
    public static final String PAYMENT_PAID   = "PAYMENT_PAID";
    public static final String ADMIN_PROMOTE  = "ADMIN_PROMOTE";
    public static final String ADMIN_DEMOTE   = "ADMIN_DEMOTE";
    public static final String ADMIN_SUSPEND  = "ADMIN_SUSPEND";
    public static final String ADMIN_REVOKE   = "ADMIN_REVOKE";

    private final AuditLogRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public void log(User user, String action, String targetType,
                    String targetId, Map<String, ?> metadata) {
        try {
            HttpServletRequest req = currentRequest();
            String ip = req == null ? null : clientIp(req);
            String ua = req == null ? null : truncate(req.getHeader("User-Agent"), 500);
            AuditLog row = AuditLog.builder()
                    .user(user)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .metadata(metadata == null ? null : safeJson(metadata))
                    .ip(ip)
                    .userAgent(ua)
                    .build();
            repo.save(row);
        } catch (Exception e) {
            log.warn("audit failed for {}: {}", action, e.getMessage());
        }
    }

    public void log(User user, String action) {
        log(user, action, null, null, null);
    }

    public void log(User user, String action, String targetType, String targetId) {
        log(user, action, targetType, targetId, null);
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception ignore) { return null; }
    }

    private String clientIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) return truncate(h.split(",")[0].trim(), 45);
        return truncate(req.getRemoteAddr(), 45);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String safeJson(Map<String, ?> m) {
        try { return mapper.writeValueAsString(m); }
        catch (Exception e) { return null; }
    }
}
