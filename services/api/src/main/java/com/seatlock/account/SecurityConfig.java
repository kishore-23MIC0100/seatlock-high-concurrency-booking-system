package com.seatlock.account;

import com.seatlock.shared.Store;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts, ObjectMapper json, Store store,
                                 @Value("${seatlock.razorpay.key-id}") String paymentKey,
                                 @Value("${seatlock.demo-staff}") boolean demoStaff,
                                 @Value("${seatlock.prometheus-open}") boolean prometheusOpen) throws Exception {
        return http
            .securityContext(context -> context.securityContextRepository(contexts))
            .addFilterAfter(new CurrentAccountFilter(store, paymentKey.strip().startsWith("rzp_live_"), demoStaff), SecurityContextHolderFilter.class)
            .requestCache(cache -> cache.disable())
            .logout(logout -> logout.disable())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/payments/razorpay/webhook"))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    json.writeValue(response.getOutputStream(),
                        Map.of("code", "SIGN_IN_REQUIRED", "message", "Sign in to continue."));
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    json.writeValue(response.getOutputStream(),
                        Map.of("code", "ACCESS_DENIED", "message", "This action is unavailable. Refresh if your session expired."));
                }))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/*",
                    "/api/v1/events/*/seats", "/api/v1/events/*/availability/stream",
                    "/api/v1/auth/csrf", "/api/v1/auth/capabilities", "/actuator/health", "/v3/api-docs").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register",
                        "/api/v1/auth/demo", "/api/v1/payments/razorpay/webhook").permitAll()
                    .requestMatchers("/api/v1/admin/**", "/api/v1/ops/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/organizer/**").hasRole("ORGANIZER");
                if (prometheusOpen) auth.requestMatchers("/actuator/prometheus").permitAll();
                auth.requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                    .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll();
            })
            .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository contexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure}") boolean secure) {
        DefaultCookieSerializer cookies = new DefaultCookieSerializer();
        cookies.setCookieName("SEATLOCK_SESSION");
        cookies.setCookiePath("/");
        cookies.setUseHttpOnlyCookie(true);
        cookies.setUseSecureCookie(secure);
        cookies.setSameSite("Lax");
        return cookies;
    }
}
