package com.livenotification.delivery.application.port;

import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.delivery.domain.Delivery;
import com.livenotification.delivery.domain.DispatchResult;
import com.livenotification.notification.application.NotificationView;

/**
 * Outbound port for channel-specific dispatch. The contract is load-bearing:
 *
 * <ol>
 *   <li><b>Implementations must catch and classify all transport failures internally</b>
 *       and return {@link DispatchResult.Success}, {@link DispatchResult.TransientFailure},
 *       or {@link DispatchResult.PermanentFailure}.</li>
 *   <li><b>Implementations must not throw.</b> Throwing is treated as a contract violation,
 *       not a transient signal — {@code DeliveryRelayService} guards by flagging the
 *       {@code notification.design.violation} counter with {@code kind=adapter_contract_violation}
 *       and forcing the delivery to DEAD. Silent retry would hide the bug.</li>
 *   <li>Classification rules follow {@code docs/document.md} §5
 *       <i>Transient vs Permanent 분류</i> as the single source of truth
 *       (e.g. {@code IOException}/{@code SocketTimeoutException}/5xx → Transient;
 *       4xx/{@code IllegalArgumentException}/payload too large → Permanent).</li>
 *   <li>{@code TimeoutException} produced by the dispatch-timeout wrapper in
 *       {@code DeliveryRelayService} is policy of the relay (not the adapter) and
 *       remains Transient. Adapters are not responsible for honoring the dispatch timeout.</li>
 * </ol>
 *
 * <p>Rationale: a single per-adapter classifier keeps transport-specific knowledge
 * (SMTP reject codes, HTTP status, broker NACK) where the exception types live. See
 * ADR-0001 for why a centralized {@code FailureClassifier} was rejected at this stage.</p>
 */
public interface ChannelAdapter {
    ChannelType type();
    DispatchResult send(NotificationView notification, Delivery delivery);
}
