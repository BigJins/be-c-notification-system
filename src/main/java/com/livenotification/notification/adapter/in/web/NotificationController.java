package com.livenotification.notification.adapter.in.web;

import com.livenotification.idempotency.domain.IdempotencyKey;
import com.livenotification.notification.adapter.in.web.dto.NotificationResponse;
import com.livenotification.notification.adapter.in.web.dto.PageResponse;
import com.livenotification.notification.adapter.in.web.dto.RegisterNotificationRequest;
import com.livenotification.notification.application.NotificationService;
import com.livenotification.notification.application.RegisterResult;
import com.livenotification.notification.domain.NotificationId;
import com.livenotification.notification.domain.RecipientId;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification")
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ResponseEntity<NotificationResponse> register(
            @Valid @RequestBody RegisterNotificationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader) {

        IdempotencyKey headerKey = idempotencyKeyHeader == null ? null : new IdempotencyKey(idempotencyKeyHeader);
        RegisterResult result = notificationService.register(request.toCommand(), headerKey);

        HttpStatus status = (result.replay() || result.eventDuplicate()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity
            .status(status)
            .header("X-Event-Duplicate", String.valueOf(result.eventDuplicate()))
            .header("X-Idempotent-Replay", String.valueOf(result.replay()))
            .body(NotificationResponse.from(result.detail()));
    }

    @GetMapping("/{id}")
    public NotificationResponse findOne(@PathVariable UUID id) {
        return NotificationResponse.from(notificationService.loadDetail(new NotificationId(id)));
    }

    @GetMapping
    public PageResponse<NotificationResponse> findByRecipient(
            @RequestParam("recipient_id") String recipientId,
            @RequestParam(value = "read", required = false) Boolean read,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(
            notificationService.findByRecipient(new RecipientId(recipientId), read, pageable),
            NotificationResponse::from);
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        notificationService.markRead(new NotificationId(id));
        return ResponseEntity.noContent().build();
    }
}
