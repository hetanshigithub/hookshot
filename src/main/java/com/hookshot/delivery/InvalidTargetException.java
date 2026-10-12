package com.hookshot.delivery;

/** Thrown when a webhook URL is malformed or points somewhere we refuse to call. */
public class InvalidTargetException extends RuntimeException {

    public InvalidTargetException(String message) {
        super(message);
    }
}
