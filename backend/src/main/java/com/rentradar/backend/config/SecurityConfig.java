package com.rentradar.backend.config;

import com.rentradar.backend.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * BCrypt work factor. 10 is the library default and is too low for a
     * password hash in 2026. 12 costs roughly 250ms per verification on
     * commodity hardware, which is the point.
     */
    private static final int BCRYPT_STRENGTH = 12;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                /*
                 * No CSRF protection, and that is correct here rather than lazy.
                 * CSRF exploits ambient credentials, meaning cookies the browser
                 * attaches on its own. This API carries its credential in an
                 * Authorization header that an attacker's page cannot set, and
                 * issues no session cookie at all. There is nothing to forge.
                 */
                .csrf(AbstractHttpConfigurer::disable)

                /* The JWT is the whole session. Nothing is held server side. */
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                /* No login form, no HTTP Basic prompt, no browser redirects. */
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        /*
                         * ERROR and ASYNC are internal forwards, not client
                         * requests. Spring Security 6 filters every dispatcher
                         * type by default, so without this the entry point
                         * rewrites every error as 401 and a 400 never reaches
                         * the caller.
                         */
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                        .permitAll()

                        /* The only genuinely public endpoints. */
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        /* Liveness for the platform. Deliberately not /actuator/**. */
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        /*
                         * API docs. Open in dev for the write up and the demo.
                         * springdoc.api-docs.enabled=false in the prod profile
                         * closes it without a code change.
                         */
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()

                        /* Everything else, including anything added later. */
                        .anyRequest().authenticated())

                /*
                 * Runs before the username and password filter so that a valid
                 * token populates the SecurityContext in time for authorization.
                 */
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)

                .exceptionHandling(handling -> handling
                        /*
                         * 401 with an empty body. No WWW-Authenticate challenge,
                         * which would make a browser pop a credential dialog, and
                         * no detail that would help an attacker tell a missing
                         * token from an expired one.
                         */
                        .authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))

                        /* Authenticated but not permitted is 403, never 401. */
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.FORBIDDEN.value())))

                .build();
    }
}