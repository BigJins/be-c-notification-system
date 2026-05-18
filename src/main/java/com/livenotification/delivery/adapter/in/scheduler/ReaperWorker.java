package com.livenotification.delivery.adapter.in.scheduler;

import com.livenotification.delivery.application.DeliveryRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReaperWorker {

    private final DeliveryRelayService relayService;

    @Scheduled(fixedDelayString = "${notification.worker.reaper-interval}")
    public void reap() {
        try {
            int recovered = relayService.releaseExpiredClaims();
            if (recovered > 0) {
                log.info("reaper recovered {} stuck delivery_attempt rows", recovered);
            }
        } catch (Exception e) {
            log.error("ReaperWorker.reap failed", e);
        }
    }
}
