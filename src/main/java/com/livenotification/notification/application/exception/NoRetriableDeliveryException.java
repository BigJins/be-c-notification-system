package com.livenotification.notification.application.exception;

public class NoRetriableDeliveryException extends RuntimeException {
    public NoRetriableDeliveryException(String msg) { super(msg); }
}
