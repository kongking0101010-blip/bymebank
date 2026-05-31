package com.khmerbank.service.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.khmerbank.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * Verifies Google OAuth ID tokens against the configured client ID.
 * Returns a small POJO; the raw token never reaches the database.
 */
@Component
@Slf4j
public class GoogleTokenVerifier {

    private final GoogleIdTokenVerifier verifier;
    private final String clientId;

    public GoogleTokenVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.clientId = clientId;
        if (clientId == null || clientId.isBlank()) {
            this.verifier = null;
            log.warn("google.client-id not set — Google sign-in will be unavailable");
        } else {
            this.verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(clientId))
                    .build();
        }
    }

    public Verified verify(String idToken) {
        if (verifier == null) {
            throw ApiException.unauthorized("GOOGLE_NOT_CONFIGURED",
                    "Google sign-in is not configured on this server.");
        }
        try {
            GoogleIdToken token = verifier.verify(idToken);
            if (token == null) {
                throw ApiException.unauthorized("GOOGLE_BAD_TOKEN",
                        "Invalid Google ID token.");
            }
            Payload p = token.getPayload();
            return new Verified(
                    p.getSubject(),
                    String.valueOf(p.getEmail()).toLowerCase(),
                    Boolean.TRUE.equals(p.getEmailVerified()),
                    (String) p.get("name"),
                    (String) p.get("picture"));
        } catch (GeneralSecurityException | java.io.IOException e) {
            log.warn("google verify failed: {}", e.getMessage());
            throw ApiException.unauthorized("GOOGLE_VERIFY_FAILED",
                    "Could not verify Google token.");
        }
    }

    public boolean isConfigured() {
        return verifier != null;
    }

    public record Verified(String sub, String email, boolean emailVerified,
                           String name, String avatarUrl) {}
}
