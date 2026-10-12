package com.hookshot.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy minJitter = new BackoffPolicy(Duration.ofSeconds(5), Duration.ofHours(1), () -> 0.0);
    private final BackoffPolicy maxJitter = new BackoffPolicy(Duration.ofSeconds(5), Duration.ofHours(1), () -> 1.0);

    @Test
    void delayDoublesEachAttempt() {
        assertThat(maxJitter.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(maxJitter.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(maxJitter.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void jitterSpansUpperHalfOfTheCeiling() {
        assertThat(minJitter.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(10));
        assertThat(maxJitter.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void delayIsCappedAndNeverOverflows() {
        assertThat(maxJitter.delayForAttempt(40)).isEqualTo(Duration.ofHours(1));
        assertThat(minJitter.delayForAttempt(40)).isEqualTo(Duration.ofMinutes(30));
        assertThat(maxJitter.delayForAttempt(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsNonPositiveAttempts() {
        assertThatThrownBy(() -> minJitter.delayForAttempt(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
