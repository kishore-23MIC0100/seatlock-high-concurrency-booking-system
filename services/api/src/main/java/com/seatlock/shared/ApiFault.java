package com.seatlock.shared;

import java.util.Map;
import org.springframework.http.HttpStatus;

public final class ApiFault extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public final Map<String, Object> details;

    public ApiFault(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ApiFault(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public static ApiFault bad(String message) {
        return new ApiFault(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    public static ApiFault missing(String message) {
        return new ApiFault(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiFault conflict(String code, String message) {
        return new ApiFault(HttpStatus.CONFLICT, code, message);
    }

    public static ApiFault forbidden(String message) {
        return new ApiFault(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
