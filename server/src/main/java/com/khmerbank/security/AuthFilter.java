package com.khmerbank.security;

import com.khmerbank.model.User;
import com.khmerbank.repository.UserRepository;
import com.khmerbank.service.apikey.ApiKeyService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Authenticates either via:
 *  - X-API-Key header (SDK clients)
 *  - Authorization: Bearer <jwt> (dashboard)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        try {
            String apiKey = req.getHeader("X-API-Key");
            String auth   = req.getHeader("Authorization");

            if (apiKey != null && !apiKey.isBlank()) {
                User user = apiKeyService.validateAndTouch(apiKey).getUser();
                SecurityContextHolder.getContext().setAuthentication(
                        new AuthenticatedUser(user, AuthenticatedUser.AuthSource.API_KEY));
            } else if (auth != null && auth.startsWith("Bearer ")) {
                String token = auth.substring(7);
                Claims claims = jwtService.parse(token);
                if ("access".equals(claims.get("type"))) {
                    UUID id = UUID.fromString(claims.getSubject());
                    User user = userRepository.findById(id).orElse(null);
                    if (user == null) {
                        // JWT is valid but the user is gone (DB reset / deleted).
                        // Tell the client to log in again, instead of returning 403.
                        SecurityContextHolder.clearContext();
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json");
                        res.getWriter().write(
                                "{\"success\":false,\"code\":\"USER_NOT_FOUND\","
                                        + "\"message\":\"Session no longer valid — please sign in again\"}");
                        return;
                    }
                    SecurityContextHolder.getContext().setAuthentication(
                            new AuthenticatedUser(user, AuthenticatedUser.AuthSource.JWT));
                }
            }
        } catch (Exception e) {
            log.debug("Auth failed: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(req, res);
    }
}
