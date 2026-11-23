package com.seatlock.booking;

import com.seatlock.shared.ApiFault;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class RazorpayClient {
    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);
    private static final String API = "https://api.razorpay.com/v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(7);

    record Order(String id, String receipt, long amount, String currency) {}
    record Payment(String id, String orderId, String status, long amount, String currency,
                   boolean captured, long amountRefunded) {
        boolean settled() {
            return (captured && "captured".equals(status)) || "refunded".equals(status);
        }
    }
    record Refund(String id, String paymentId, String status, long amount) {}

    private final ObjectMapper json;
    private final HttpClient transport;
    private final boolean testOnly;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;

    RazorpayClient(ObjectMapper json, @Value("${seatlock.razorpay.test-only}") boolean testOnly,
                   @Value("${seatlock.razorpay.key-id}") String keyId,
                   @Value("${seatlock.razorpay.key-secret}") String keySecret,
                   @Value("${seatlock.razorpay.webhook-secret}") String webhookSecret) {
        this.json = json;
        this.testOnly = testOnly;
        this.keyId = keyId.strip();
        this.keySecret = keySecret.strip();
        this.webhookSecret = webhookSecret;
        this.transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    boolean configured() {
        return keyId.matches("rzp_(test|live)_[A-Za-z0-9]+") && !keySecret.isBlank()
            && (!testOnly || keyId.startsWith("rzp_test_"));
    }

    String mode() {
        if (testOnly || keyId.startsWith("rzp_test_")) return "TEST";
        return keyId.startsWith("rzp_live_") ? "LIVE" : null;
    }

    String publicKey() {
        requireConfigured();
        return keyId;
    }

    void requireConfigured() {
        if (!configured()) {
            throw new ApiFault(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_UNAVAILABLE", "Online payments are currently unavailable.");
        }
    }

    Optional<Order> orderByReceipt(UUID receipt) {
        JsonNode results = exchange("GET", "/orders?receipt=" + receipt + "&count=100", null, null).path("items");
        for (JsonNode order : results) {
            if (receipt.toString().equals(order.path("receipt").asText())) return Optional.of(toOrder(order));
        }
        return Optional.empty();
    }

    Order createOrder(UUID receipt, long amount) {
        return toOrder(exchange("POST", "/orders",
            Map.of("amount", amount, "currency", "INR", "receipt", receipt.toString(), "partial_payment", false), null));
    }

    Payment payment(String id) {
        return toPayment(exchange("GET", "/payments/" + identifier(id, "pay"), null, null));
    }

    List<Payment> paymentsFor(String order) {
        JsonNode items = exchange("GET", "/orders/" + identifier(order, "order") + "/payments", null, null).path("items");
        var payments = new java.util.ArrayList<Payment>();
        items.forEach(item -> payments.add(toPayment(item)));
        return payments;
    }

    Refund startRefund(UUID refund, String payment, long amount) {
        return toRefund(exchange("POST", "/payments/" + identifier(payment, "pay") + "/refund",
            Map.of("amount", amount, "speed", "normal", "receipt", refund.toString()), refund));
    }

    Refund refund(String refund) {
        return toRefund(exchange("GET", "/refunds/" + identifier(refund, "rfnd"), null, null));
    }

    boolean checkoutSignature(String order, String payment, String signature) {
        requireConfigured();
        return matches(keySecret, (order + "|" + payment).getBytes(StandardCharsets.UTF_8), signature);
    }

    boolean webhookSignature(byte[] body, String signature) {
        return !webhookSecret.isBlank() && matches(webhookSecret, body, signature);
    }

    static boolean matches(String key, byte[] data, String signature) {
        if (signature == null || !signature.matches("[0-9a-fA-F]{64}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(data), HexFormat.of().parseHex(signature));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Payment signature verification is unavailable", error);
        }
    }

    private JsonNode exchange(String method, String path, Map<String, Object> body, UUID refundKey) {
        requireConfigured();
        try {
            String access = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create(API + path)).timeout(TIMEOUT)
                .header("Authorization", "Basic " + access).header("Accept", "application/json");
            if (refundKey != null) request.header("X-Refund-Idempotency", refundKey.toString());
            if (body == null) request.GET();
            else request.method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                .header("Content-Type", "application/json");
            var response = transport.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.warn("Razorpay {} returned HTTP {}", method, response.statusCode());
                throw providerUnavailable();
            }
            if (response.body().length > 1_048_576) throw providerUnavailable();
            JsonNode result = json.readTree(response.body());
            if (result == null || !result.isObject()) throw providerUnavailable();
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw providerUnavailable();
        } catch (IOException | JacksonException ex) {
            log.warn("Razorpay {} could not be completed ({})", method, ex.getClass().getSimpleName());
            throw providerUnavailable();
        }
    }

    private static ApiFault providerUnavailable() {
        return new ApiFault(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_UNAVAILABLE",
            "Razorpay is temporarily unavailable. Check the payment status before trying again.");
    }

    private static String identifier(String value, String type) {
        if (value == null || !value.matches(type + "_[A-Za-z0-9]{4,60}")) {
            throw ApiFault.bad("Invalid Razorpay reference.");
        }
        return value;
    }

    private static Order toOrder(JsonNode item) {
        return new Order(identifier(item.path("id").asText(), "order"), item.path("receipt").asText(),
            item.path("amount").asLong(-1), item.path("currency").asText());
    }

    private static Payment toPayment(JsonNode item) {
        return new Payment(identifier(item.path("id").asText(), "pay"), item.path("order_id").asText(),
            item.path("status").asText(), item.path("amount").asLong(-1), item.path("currency").asText(),
            item.path("captured").asBoolean(), item.path("amount_refunded").asLong(-1));
    }

    private static Refund toRefund(JsonNode item) {
        if (!"INR".equals(item.path("currency").asText())) throw providerUnavailable();
        return new Refund(identifier(item.path("id").asText(), "rfnd"), item.path("payment_id").asText(),
            item.path("status").asText(), item.path("amount").asLong(-1));
    }
}
