package com.seatlock.account;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Accounts {
    public record Profile(UUID id, String email, String displayName, String role, String organizerStatus) {}
    public record Account(Profile profile, String passwordHash, boolean enabled) {}

    private final Store store;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    Accounts(Store store, PasswordEncoder passwords) {
        this.store = store;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public Profile authenticate(String email, String password) {
        Account account = byEmail(email);
        String hash = account == null ? dummyHash : account.passwordHash;
        boolean matches = password.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(password, hash);
        if (account == null || !matches || !account.enabled) {
            throw new ApiFault(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "The email or password is incorrect.");
        }
        return account.profile;
    }

    public Profile profile(UUID id) {
        return store.db.sql("""
            SELECT u.id,u.email,u.display_name,u.role,o.status AS organizer_status
            FROM users u LEFT JOIN organizers o ON o.user_id=u.id WHERE u.id=:id AND u.enabled
            """).param("id", id).query(Profile.class).optional()
            .orElseThrow(() -> ApiFault.missing("Account not found."));
    }

    @Transactional
    public Profile register(String email, String displayName, String password) {
        return create(email, displayName, password, "CUSTOMER");
    }

    public Profile create(String email, String displayName, String password, String role) {
        if (password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || displayName.strip().isBlank()) {
            throw ApiFault.bad("Use a display name and a password of at least 10 characters and at most 72 bytes.");
        }
        UUID id = store.db.sql("""
            INSERT INTO users(email,display_name,password_hash,role) VALUES(:email,:name,:hash,:role)
            ON CONFLICT (email) DO NOTHING RETURNING id
            """).param("email", normalize(email)).param("name", displayName.strip())
            .param("hash", passwords.encode(password)).param("role", role).query(UUID.class).optional()
            .orElseThrow(() -> ApiFault.conflict("ACCOUNT_EXISTS", "An account with that email already exists."));
        return profile(id);
    }

    Account byEmail(String email) {
        return store.db.sql("""
            SELECT u.id,u.email,u.display_name,u.role,u.password_hash,u.enabled,o.status
            FROM users u LEFT JOIN organizers o ON o.user_id=u.id WHERE u.email=:email
            """).param("email", normalize(email)).query((rs, row) -> new Account(
                new Profile(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                    rs.getString("role"), rs.getString("status")),
                rs.getString("password_hash"), rs.getBoolean("enabled"))).optional().orElse(null);
    }

    @Transactional
    public Profile rename(UUID id, String displayName) {
        if (displayName.strip().isEmpty()) throw ApiFault.bad("Enter a display name.");
        store.db.sql("UPDATE users SET display_name=:name WHERE id=:id")
            .param("name", displayName.strip()).param("id", id).update();
        return profile(id);
    }

    @Transactional
    public Profile apply(UUID user, String name) {
        if (name.strip().isEmpty()) throw ApiFault.bad("Enter an organizer name.");
        Profile profile = profile(user);
        if (!profile.role.equals("CUSTOMER")) throw ApiFault.forbidden("This account cannot apply.");
        int changed = store.db.sql("""
            INSERT INTO organizers(user_id,name) VALUES(:user,:name)
            ON CONFLICT (user_id) DO UPDATE SET name=excluded.name,status='PENDING'
            WHERE organizers.status='REJECTED'
            """).param("user", user).param("name", name.strip()).update();
        if (changed == 0) throw ApiFault.conflict("ALREADY_APPLIED", "Your application is already pending.");
        store.audit(user, "ORGANIZER_APPLIED", user, null);
        return profile(user);
    }

    public List<Profile> users() {
        return store.db.sql("""
            SELECT u.id,u.email,u.display_name,u.role,o.status AS organizer_status
            FROM users u LEFT JOIN organizers o ON o.user_id=u.id ORDER BY u.created_at DESC LIMIT 200
            """).query(Profile.class).list();
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
