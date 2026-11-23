package com.seatlock.account;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    record Credentials(@NotBlank @Email @Size(max = 180) String email, @NotBlank @Size(max = 100) String password) {}
    record Registration(@NotBlank @Email @Size(max = 180) String email, @NotBlank @Size(min = 10, max = 72) String password,
                        @NotBlank @Size(max = 100) String displayName) {}
    record Name(@NotBlank @Size(max = 100) String displayName) {}
    record Demo(@NotBlank String role) {}

    private final Accounts accounts;
    private final SecurityContextRepository contexts;
    private final RateLimits limits;
    private final boolean seed;

    AuthController(Accounts accounts, SecurityContextRepository contexts, RateLimits limits,
                   @Value("${seatlock.seed}") boolean seed, @Value("${seatlock.razorpay.key-id}") String paymentKey) {
        this.accounts = accounts;
        this.contexts = contexts;
        this.limits = limits;
        this.seed = seed && !paymentKey.strip().startsWith("rzp_live_");
    }

    @GetMapping("/auth/csrf")
    Map<String, String> csrf(CsrfToken csrf, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
    }

    @GetMapping("/auth/capabilities")
    Map<String, Boolean> capabilities() {
        return Map.of("demo", seed);
    }

    @PostMapping("/auth/login")
    Accounts.Profile login(@RequestBody @Valid Credentials body, HttpServletRequest request, HttpServletResponse response) {
        limits.check("login-ip", request.getRemoteAddr(), 30, Duration.ofMinutes(1));
        return signIn(accounts.authenticate(body.email, body.password), request, response);
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    Accounts.Profile register(@RequestBody @Valid Registration body, HttpServletRequest request, HttpServletResponse response) {
        limits.check("register-ip", request.getRemoteAddr(), 12, Duration.ofMinutes(1));
        return signIn(accounts.register(body.email, body.displayName, body.password), request, response);
    }

    @PostMapping("/auth/demo")
    Accounts.Profile demo(@RequestBody @Valid Demo body, HttpServletRequest request, HttpServletResponse response) {
        if (!seed) throw ApiFault.missing("Demo sessions are disabled.");
        limits.check("demo-ip", request.getRemoteAddr(), 30, Duration.ofMinutes(1));
        String email = switch (body.role) {
            case "CUSTOMER" -> null;
            case "ADMIN" -> DemoSeed.ADMIN;
            case "ORGANIZER" -> DemoSeed.ORGANIZER;
            default -> throw ApiFault.bad("Choose a valid demo role.");
        };
        Accounts.Profile profile = email == null
            ? accounts.register("visitor-" + UUID.randomUUID() + "@demo.seatlock.test", "Guest listener", UUID.randomUUID().toString())
            : accounts.byEmail(email).profile();
        return signIn(profile, request, response);
    }

    private Accounts.Profile signIn(Accounts.Profile profile, HttpServletRequest request, HttpServletResponse response) {
        var previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(profile.id().toString(), null,
            List.of(new SimpleGrantedAuthority("ROLE_" + profile.role()))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return profile;
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    Accounts.Profile me(Principal principal) {
        return accounts.profile(user(principal));
    }

    @PatchMapping("/me")
    Accounts.Profile rename(Principal principal, @RequestBody @Valid Name body) {
        return accounts.rename(user(principal), body.displayName);
    }

    @PostMapping("/me/organizer-application")
    Accounts.Profile apply(Principal principal, @RequestBody @Valid Name body) {
        return accounts.apply(user(principal), body.displayName);
    }

    public static UUID user(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
