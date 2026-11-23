package com.seatlock.shared;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    record ErrorResponse(String code, String message, Map<String, Object> details) {}

    @ExceptionHandler(ApiFault.class)
    ResponseEntity<ErrorResponse> fault(ApiFault error) {
        return ResponseEntity.status(error.status)
            .body(new ErrorResponse(error.code, error.getMessage(), error.details));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException error) {
        FieldError field = error.getBindingResult().getFieldError();
        return fault(ApiFault.bad(field == null ? "Review the supplied fields."
            : field.getField() + " " + field.getDefaultMessage()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> malformed() {
        return fault(ApiFault.bad("The request contains an invalid field or format."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden() {
        return fault(ApiFault.forbidden("You do not have access to this action."));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> database(DataAccessException error) {
        Throwable cause = error.getMostSpecificCause();
        if (cause instanceof SQLException sql
                && Set.of("55P03", "40P01", "40001", "57014").contains(sql.getSQLState())) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(new ErrorResponse("BUSY", "Inventory is busy. Retry with the same request key.", Map.of()));
        }
        log.error("Database operation failed: {}", error.getClass().getSimpleName());
        return fault(new ApiFault(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
            "SeatLock is temporarily unavailable. Try again shortly."));
    }
}
