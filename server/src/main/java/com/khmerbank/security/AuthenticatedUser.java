package com.khmerbank.security;

import com.khmerbank.model.User;
import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authentication holder. Use either JWT (for dashboard) or API Key (for SDK).
 */
@Getter
public class AuthenticatedUser extends AbstractAuthenticationToken {

    private final User user;
    private final AuthSource source;

    public AuthenticatedUser(User user, AuthSource source) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.user = user;
        this.source = source;
        setAuthenticated(true);
    }

    @Override public Object getCredentials() { return null; }
    @Override public Object getPrincipal()   { return user; }
    @Override public String getName()        { return user.getEmail(); }

    public enum AuthSource { JWT, API_KEY }
}
