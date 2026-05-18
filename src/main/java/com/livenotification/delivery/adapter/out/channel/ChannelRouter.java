package com.livenotification.delivery.adapter.out.channel;

import com.livenotification.delivery.application.port.ChannelAdapter;
import com.livenotification.delivery.domain.ChannelType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ChannelRouter {

    private final List<ChannelAdapter> adapters;
    private Map<ChannelType, ChannelAdapter> byType;

    @PostConstruct
    void init() {
        byType = adapters.stream()
            .collect(Collectors.toMap(ChannelAdapter::type, Function.identity()));
    }

    public ChannelAdapter route(ChannelType type) {
        ChannelAdapter adapter = byType.get(type);
        if (adapter == null) {
            throw new IllegalStateException("no adapter for channel: " + type);
        }
        return adapter;
    }
}
