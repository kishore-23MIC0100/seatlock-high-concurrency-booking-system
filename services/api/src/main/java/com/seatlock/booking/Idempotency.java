package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import com.seatlock.shared.Store;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class Idempotency {
    record Claim(String requestHash, UUID resultId) {}
    private final Store store;

    Idempotency(Store store) {
        this.store = store;
    }

    UUID claim(UUID user, String operation, String key, String canonicalPayload) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,100}")) {
            throw ApiFault.bad("Supply a valid Idempotency-Key header.");
        }
        String hash = Store.digest(canonicalPayload);
        store.db.sql("""
            INSERT INTO idempotency_keys(user_id,operation,key,request_hash)
            VALUES(:user,:operation,:key,:hash)
            ON CONFLICT (user_id,operation,key) DO NOTHING
            """).param("user", user).param("operation", operation).param("key", key).param("hash", hash).update();
        Claim claim = store.db.sql("""
            SELECT request_hash,result_id FROM idempotency_keys
            WHERE user_id=:user AND operation=:operation AND key=:key FOR UPDATE
            """).param("user", user).param("operation", operation).param("key", key).query(Claim.class).single();
        if (!hash.equals(claim.requestHash)) {
            throw ApiFault.conflict("IDEMPOTENCY_MISMATCH", "That key was already used for a different request.");
        }
        return claim.resultId;
    }

    void complete(UUID user, String operation, String key, UUID result) {
        store.db.sql("""
            UPDATE idempotency_keys SET result_id=:result
            WHERE user_id=:user AND operation=:operation AND key=:key
            """).param("user", user).param("operation", operation).param("key", key).param("result", result).update();
    }
}
