package com.livenotification.notification.application.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String msg) { super(msg); }
}
