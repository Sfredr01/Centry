package com.centry.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.jdbc.JdbcDaoImpl;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

/**
 * Two kinds of clients share one filter chain:
 * <ul>
 *   <li>browsers: form login on our own {@code /login} page, session cookie, persistent remember-me;</li>
 *   <li>API clients (curl, phone shortcuts): HTTP Basic on {@code /api/**}.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class SecurityConfig {

    static final Duration REMEMBER_ME_VALIDITY = Duration.ofDays(30);

    private static final String API = "/api/**";

    @Bean
    PasswordEncoder passwordEncoder() {
        // Delegating encoder: new hashes are "{bcrypt}..." and the algorithm can change later without a migration.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Stock {@link JdbcDaoImpl} over {@code users}. A NULL password_hash is filtered out in SQL, so a member
     * without a password simply does not exist as far as authentication is concerned.
     */
    @Bean
    UserDetailsService userDetailsService(DataSource dataSource) {
        JdbcDaoImpl users = new JdbcDaoImpl();
        users.setDataSource(dataSource);
        users.setUsersByUsernameQuery(
                "SELECT username, password_hash, 1 FROM users WHERE username = ? AND password_hash IS NOT NULL");
        users.setAuthoritiesByUsernameQuery("SELECT username, 'ROLE_' || role FROM users WHERE username = ?");
        return users;
    }

    @Bean
    PersistentTokenRepository rememberMeTokens(DataSource dataSource) {
        JdbcTokenRepositoryImpl tokens = new JdbcTokenRepositoryImpl();
        tokens.setDataSource(dataSource);
        return tokens;
    }

    @Bean
    PersistentTokenBasedRememberMeServices rememberMeServices(UserDetailsService users,
            PersistentTokenRepository tokens) {
        // The key only signs in-process RememberMeAuthenticationTokens; the persistent-token approach keeps its
        // real state in persistent_logins, so a random key per start is fine and needs no secret in config.
        PersistentTokenBasedRememberMeServices services =
                new PersistentTokenBasedRememberMeServices(UUID.randomUUID().toString(), users, tokens);
        services.setTokenValiditySeconds((int) REMEMBER_ME_VALIDITY.toSeconds());
        return services;
    }

    /**
     * The HTTP rules need a servlet application. Without one (the non-web contexts some tests start) only
     * the beans above exist, because HttpSecurity is not available there.
     */
    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebSecurity {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http,
                PersistentTokenBasedRememberMeServices rememberMeServices) {
            // Non-default: a bare 401 with NO WWW-Authenticate header. A Basic challenge would make browsers show a
            // password prompt and then cache the credentials and re-send them on their own (which is what makes
            // Basic CSRF-able). API clients send Basic pre-emptively and need no challenge.
            HttpStatusEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
            RequestMatcher api = PathPatternRequestMatcher.pathPattern(API);

            http
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                            // Everything the login page needs to render, plus the PWA manifest and icons.
                            .requestMatchers("/manifest.webmanifest", "/vendor/**", "/css/**", "/icons/**",
                                    "/favicon.ico").permitAll()
                            // The browser UI's budget forms; the matching API routes below are the same rule.
                            .requestMatchers(HttpMethod.POST, "/budgets", "/budgets/**").hasRole("ADMIN")
                            .requestMatchers(HttpMethod.PUT, "/api/v1/budgets/**").hasRole("ADMIN")
                            .requestMatchers(HttpMethod.DELETE, "/api/v1/budgets/**").hasRole("ADMIN")
                            .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                            // The whole ledger leaves the app here, so it is the admin's call.
                            .requestMatchers("/export/**").hasRole("ADMIN")
                            .requestMatchers(API).hasAnyRole("ADMIN", "MEMBER")
                            .anyRequest().authenticated())
                    .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                    .formLogin(form -> form.loginPage("/login").permitAll())
                    // permitAll() lets the anonymous browser reach the post-logout /login?logout page.
                    .logout(logout -> logout.permitAll())
                    .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
                    // Anonymous API calls get the bare 401; every other path keeps form login's redirect to /login.
                    .exceptionHandling(handling -> handling.defaultAuthenticationEntryPointFor(unauthorized, api))
                    // Non-default: CSRF stays on for browser/session requests and is skipped only for /api requests
                    // that carry an Authorization header. That is safe because browsers never attach Basic credentials
                    // by themselves here (no challenge is ever sent, see above), and a cross-site page cannot set an
                    // Authorization header without a CORS preflight, which this app never allows.
                    .csrf(csrf -> csrf.ignoringRequestMatchers(
                            new AndRequestMatcher(api, new RequestHeaderRequestMatcher("Authorization"))));
            return http.build();
        }
    }
}
