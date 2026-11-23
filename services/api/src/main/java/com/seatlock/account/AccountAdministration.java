package com.seatlock.account;

import static com.seatlock.account.AuthController.user;

import com.seatlock.booking.Tickets.AuditEntry;
import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
class AccountAdministration {
    record UserRow(UUID id, String email, String displayName, String role, boolean enabled, OffsetDateTime createdAt) {}
    record Application(UUID userId, String name, String email, String status, OffsetDateTime createdAt) {}
    record Decision(@NotNull Boolean approved) {}
    record Status(@NotNull Boolean enabled) {}
    private final Store store;

    AccountAdministration(Store store) {
        this.store = store;
    }

    @GetMapping("/users")
    List<UserRow> users() {
        return store.db.sql("SELECT id,email,display_name,role,enabled,created_at FROM users ORDER BY created_at DESC LIMIT 250")
            .query(UserRow.class).list();
    }

    @PatchMapping("/users/{id}")
    @Transactional
    UserRow enabled(Principal principal, @PathVariable UUID id, @RequestBody @Valid Status request) {
        int updated = store.db.sql("UPDATE users SET enabled=:enabled WHERE id=:id AND role<>'ADMIN'")
            .param("id", id).param("enabled", request.enabled).update();
        if (updated == 0) throw ApiFault.missing("A manageable account was not found.");
        store.audit(user(principal), request.enabled ? "ACCOUNT_ENABLED" : "ACCOUNT_DISABLED", id, null);
        store.db.sql("DELETE FROM spring_session WHERE principal_name=:id").param("id", id.toString()).update();
        return store.db.sql("SELECT id,email,display_name,role,enabled,created_at FROM users WHERE id=:id")
            .param("id", id).query(UserRow.class).single();
    }

    @GetMapping("/organizers")
    List<Application> applications() {
        return store.db.sql("""
            SELECT o.user_id,o.name,u.email,o.status,o.created_at FROM organizers o JOIN users u ON u.id=o.user_id
            ORDER BY (o.status='PENDING') DESC,o.created_at DESC LIMIT 250
            """).query(Application.class).list();
    }

    @PostMapping("/organizers/{id}/decision")
    @Transactional
    Application decide(Principal principal, @PathVariable UUID id, @RequestBody @Valid Decision decision) {
        String status = decision.approved ? "APPROVED" : "REJECTED";
        int changed = store.db.sql("UPDATE organizers SET status=:status WHERE user_id=:id AND status='PENDING'")
            .param("id", id).param("status", status).update();
        if (changed == 0) throw ApiFault.conflict("APPLICATION_REVIEWED", "There is no pending application for this account.");
        if (decision.approved) {
            store.db.sql("UPDATE users SET role='ORGANIZER' WHERE id=:id AND role='CUSTOMER'").param("id", id).update();
            store.db.sql("DELETE FROM spring_session WHERE principal_name=:id").param("id", id.toString()).update();
        }
        store.audit(user(principal), "ORGANIZER_" + status, id, null);
        return store.db.sql("""
            SELECT o.user_id,o.name,u.email,o.status,o.created_at FROM organizers o JOIN users u ON u.id=o.user_id
            WHERE o.user_id=:id
            """).param("id", id).query(Application.class).single();
    }

    @GetMapping("/audit")
    List<AuditEntry> audit() {
        return store.db.sql("SELECT id,actor_id,action,target,detail,created_at FROM audit_logs ORDER BY id DESC LIMIT 250")
            .query(AuditEntry.class).list();
    }
}
