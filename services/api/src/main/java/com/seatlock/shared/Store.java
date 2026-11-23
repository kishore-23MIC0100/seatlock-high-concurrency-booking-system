package com.seatlock.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Store {
    public final JdbcClient db;

    public Store(JdbcClient db) {
        this.db = db;
    }

    public OffsetDateTime now() {
        return db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
    }

    public void boundLocks() {
        db.sql("SET LOCAL lock_timeout = '2500ms'").update();
    }

    public void inventoryChanged(String event, String type, UUID subject, UUID user) {
        db.sql("UPDATE events SET revision = revision + 1 WHERE id = :event")
            .param("event", event).update();
        outbox(event, type, subject, user);
    }

    public void outbox(String event, String type, UUID subject, UUID user) {
        db.sql("""
            INSERT INTO outbox_events(event_id,event_type,subject_id,user_id)
            VALUES (:event,:type,:subject,:user)
            """).param("event", event).param("type", type).param("subject", subject)
            .param("user", user).update();
    }

    public void audit(UUID actor, String action, Object target, String detail) {
        db.sql("INSERT INTO audit_logs(actor_id,action,target,detail) VALUES (:actor,:action,:target,:detail)")
            .param("actor", actor).param("action", action).param("target", target.toString())
            .param("detail", detail).update();
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
