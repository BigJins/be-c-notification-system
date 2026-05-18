package com.livenotification.delivery.application;

import com.livenotification.delivery.domain.DeliveryId;

public interface DeliveryRetryRegistrar {
    void issueNewAttempt(DeliveryId deliveryId);
}
