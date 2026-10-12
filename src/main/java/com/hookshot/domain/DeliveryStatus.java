package com.hookshot.domain;

public enum DeliveryStatus {
    /** Waiting for its first attempt. */
    PENDING,
    /** Claimed by a worker; the lease on next_attempt_at protects against worker crashes. */
    IN_FLIGHT,
    SUCCEEDED,
    /** Last attempt failed; will be retried at next_attempt_at. */
    FAILED,
    /** Out of attempts (or the receiver said 410 Gone). Can be re-queued manually. */
    DEAD
}
