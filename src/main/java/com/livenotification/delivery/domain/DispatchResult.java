package com.livenotification.delivery.domain;

public sealed interface DispatchResult
    permits DispatchResult.Success,
            DispatchResult.TransientFailure,
            DispatchResult.PermanentFailure {

    record Success() implements DispatchResult {}
    record TransientFailure(String reason, Throwable cause) implements DispatchResult {}
    record PermanentFailure(String reason, Throwable cause) implements DispatchResult {}
}
