package com.hookshot.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignaturesTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"order.created\"}";
    private static final long T = 1_700_000_000L;

    @Test
    void matchesIndependentlyComputedHmac() {
        // Computed with Python: hmac.new(secret, f"{t}.{body}", sha256).hexdigest()
        assertThat(Signatures.hmacHex(SECRET, T, BODY))
                .isEqualTo("479d263a26872ce2aeda9f217b43b4c9870314fba787aeb383c38d7c7a296e24");
    }

    @Test
    void headerHasTimestampAndSignature() {
        assertThat(Signatures.header(SECRET, T, BODY))
                .isEqualTo("t=1700000000,v1=479d263a26872ce2aeda9f217b43b4c9870314fba787aeb383c38d7c7a296e24");
    }

    @Test
    void verifyAcceptsValidSignature() {
        String header = Signatures.header(SECRET, T, BODY);
        assertThat(Signatures.verify(SECRET, header, BODY, T + 100, 300)).isTrue();
    }

    @Test
    void verifyRejectsTamperedBody() {
        String header = Signatures.header(SECRET, T, BODY);
        assertThat(Signatures.verify(SECRET, header, BODY + " ", T + 100, 300)).isFalse();
    }

    @Test
    void verifyRejectsWrongSecret() {
        String header = Signatures.header(SECRET, T, BODY);
        assertThat(Signatures.verify("another_secret", header, BODY, T + 100, 300)).isFalse();
    }

    @Test
    void verifyRejectsReplayOutsideTolerance() {
        String header = Signatures.header(SECRET, T, BODY);
        assertThat(Signatures.verify(SECRET, header, BODY, T + 1_000, 300)).isFalse();
    }

    @Test
    void verifyRejectsMissingOrMalformedHeader() {
        assertThat(Signatures.verify(SECRET, null, BODY, T, 300)).isFalse();
        assertThat(Signatures.verify(SECRET, "garbage", BODY, T, 300)).isFalse();
        assertThat(Signatures.verify(SECRET, "t=abc,v1=00", BODY, T, 300)).isFalse();
    }
}
