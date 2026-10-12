package com.hookshot.delivery;

/** Outcome of one HTTP attempt. {@code statusCode} is null when no response was received. */
public record DeliveryResult(Integer statusCode, String error) {

    public boolean success() {
        return statusCode != null && statusCode >= 200 && statusCode < 300;
    }

    /** 410 Gone means the receiver has intentionally removed this endpoint: retrying is pointless. */
    public boolean permanentFailure() {
        return statusCode != null && statusCode == 410;
    }
}
