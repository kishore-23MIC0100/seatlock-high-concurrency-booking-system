package com.seatlock.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.shared.ApiFault;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RazorpaySignaturesTest {
    @Test
    void demoAcceptsOnlyTestKeys() {
        var missing = new RazorpayClient(new ObjectMapper(), true, "", "", "");
        assertThat(missing.mode()).isEqualTo("TEST");
        assertThat(missing.configured()).isFalse();

        var test = new RazorpayClient(new ObjectMapper(), true, "rzp_test_Example", "fixture", "");
        assertThat(test.publicKey()).isEqualTo("rzp_test_Example");

        var live = new RazorpayClient(new ObjectMapper(), true, "rzp_live_Example", "fixture", "");
        assertThatThrownBy(live::publicKey).isInstanceOfSatisfying(ApiFault.class,
            fault -> assertThat(fault.code).isEqualTo("PAYMENTS_UNAVAILABLE"));

        var production = new RazorpayClient(new ObjectMapper(), false, "rzp_live_Example", "fixture", "");
        assertThat(production.mode()).isEqualTo("LIVE");
        assertThat(production.publicKey()).isEqualTo("rzp_live_Example");
    }

    @Test
    void verifiesExactBytesAndRejectsChangedOrMalformedSignatures() {
        String payload = "The quick brown fox jumps over the lazy dog";
        String signature = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";
        assertThat(RazorpayClient.matches("key", payload.getBytes(StandardCharsets.UTF_8), signature)).isTrue();
        assertThat(RazorpayClient.matches("key", (payload + "\n").getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(RazorpayClient.matches("other", payload.getBytes(StandardCharsets.UTF_8), signature)).isFalse();
        assertThat(RazorpayClient.matches("key", payload.getBytes(StandardCharsets.UTF_8), "invalid")).isFalse();
    }
}
