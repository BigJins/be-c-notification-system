package com.livenotification.delivery.application.port;

import com.livenotification.delivery.domain.ChannelType;

public interface ChannelAdapterRouter {
    ChannelAdapter route(ChannelType type);
}
