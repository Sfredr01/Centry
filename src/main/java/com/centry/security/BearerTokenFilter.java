package com.centry.security;

import com.centry.repository.ApiTokenRepository;
import com.centry.repository.ApiTokenRepository.Owner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates {@code Authorization: Bearer <token>} as the token's owner, for one endpoint only: recording an
 * expense. A token is meant for a phone shortcut, so a leaked one can add an expense but cannot read data, mint
 * more tokens or reach the browser UI. A bad token is a bare 401; a good one anywhere else is 403.
 *
 * <p>The context is never saved to a session, so every bearer request is authenticated on its own.
 */
final class BearerTokenFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer";
    private static final String EXPENSES = "/api/v1/expenses";

    private final ApiTokenRepository tokens;
    private final Clock clock;

    BearerTokenFilter(ApiTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<Owner> owner = authenticate(header.substring(SCHEME.length()).strip());
        if (owner.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!isExpenseCreate(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        tokens.touch(owner.get().tokenId(), LocalDateTime.now(clock));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(owner.get().username(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + owner.get().role().name()))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private Optional<Owner> authenticate(String token) {
        return ApiTokens.isWellFormed(token) ? tokens.findOwner(ApiTokens.hash(token)) : Optional.empty();
    }

    private static boolean isExpenseCreate(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && EXPENSES.equals(request.getRequestURI());
    }
}
