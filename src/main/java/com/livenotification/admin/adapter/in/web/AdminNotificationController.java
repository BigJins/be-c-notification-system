package com.livenotification.admin.adapter.in.web;

import com.livenotification.admin.application.AdminRetryService;
import com.livenotification.notification.domain.NotificationId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminRetryService retryService;

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID id) {
        retryService.retry(new NotificationId(id));
        return ResponseEntity.noContent().build();
    }
}
