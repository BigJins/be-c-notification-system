package com.livenotification.notification.application;

import com.livenotification.notification.domain.NotificationId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class NotificationLookupImpl implements NotificationLookup {
    private final NotificationRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationView> findById(NotificationId id) {
        return repository.findById(id).map(NotificationView::from);
    }
}
