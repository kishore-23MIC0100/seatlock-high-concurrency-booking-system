package com.seatlock.account;

import com.seatlock.shared.Store;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class CurrentAccountFilter extends OncePerRequestFilter {
    private final Store store;
    private final boolean livePayments;

    CurrentAccountFilter(Store store, boolean livePayments) {
        this.store = store;
        this.livePayments = livePayments;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof UsernamePasswordAuthenticationToken) {
            UUID id = UUID.fromString(authentication.getName());
            String role = store.db.sql("""
                SELECT role FROM users WHERE id=:id AND enabled
                  AND (NOT :live OR email NOT LIKE '%@demo.seatlock.test')
                """).param("id", id).param("live", livePayments).query(String.class).optional().orElse(null);
            if (role == null || authentication.getAuthorities().stream().noneMatch(auth -> auth.getAuthority().equals("ROLE_" + role))) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
