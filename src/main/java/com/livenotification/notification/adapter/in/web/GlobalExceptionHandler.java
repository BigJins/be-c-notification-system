package com.livenotification.notification.adapter.in.web;

import com.livenotification.notification.application.exception.IdempotencyConflictException;
import com.livenotification.notification.application.exception.NoRetriableDeliveryException;
import com.livenotification.notification.application.exception.ReadStateViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadInput(IllegalArgumentException e) {
        return problem(BAD_REQUEST, "invalid-input", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e) {
        return problem(CONFLICT, "idempotency-conflict", e.getMessage());
    }

    @ExceptionHandler(NoRetriableDeliveryException.class)
    public ProblemDetail handleNoRetriable(NoRetriableDeliveryException e) {
        return problem(NOT_FOUND, "no-retriable-delivery", e.getMessage());
    }

    @ExceptionHandler(ReadStateViolationException.class)
    public ProblemDetail handleReadState(ReadStateViolationException e) {
        return problem(UNPROCESSABLE_ENTITY, "read-state-violation", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException e) {
        return problem(UNPROCESSABLE_ENTITY, "domain-state-violation", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAny(Exception e) {
        log.error("unexpected error", e);
        return problem(INTERNAL_SERVER_ERROR, "unexpected-error", "unexpected error");
    }

    private ProblemDetail problem(HttpStatus status, String slug, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://livenotification.com/problems/" + slug));
        p.setTitle(slug);
        return p;
    }
}
