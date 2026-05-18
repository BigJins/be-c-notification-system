package com.livenotification.delivery.application;

import com.livenotification.delivery.domain.DeliveryAttempt;
import com.livenotification.delivery.domain.DeliveryId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class DeliveryRetryRegistrarImpl implements DeliveryRetryRegistrar {

    private final DeliveryAttemptRepository attemptRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueNewAttempt(DeliveryId deliveryId) {
        attemptRepository.save(DeliveryAttempt.readyFor(deliveryId, clock));
    }
}
