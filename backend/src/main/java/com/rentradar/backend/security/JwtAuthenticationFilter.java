package com.rentradar.backend.security;

import com.rentradar.backend.domain.type.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Establishes the security context from a bearer token.
 *
 * The filter never rejects a request itself. An absent or invalid token leaves
 * the context empty, and the authorisation rules decide what that means: the
 * same missing token is fine on a public route and a 401 on a protected one.
 * Rejecting here would duplicate that decision in two places.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        extractToken(request)
                .flatMap(jwtService::verify)
                .ifPresent(this::authenticate);

        chain.doFilter(request, response);
    }

    private java.util.Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return java.util.Optional.empty();
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(token);
    }

    private void authenticate(JwtService.VerifiedToken verified) {
        UserRole role;
        try {
            role = UserRole.valueOf(verified.role());
        } catch (IllegalArgumentException e) {
            // A role that no longer exists means a stale or tampered token.
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(
                verified.userId(), role, verified.marketId());

// Spring's hasRole() expects the ROLE_ prefix; hasAuthority() does not.
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));

        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);
}
}