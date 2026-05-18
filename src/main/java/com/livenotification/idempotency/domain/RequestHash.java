package com.livenotification.idempotency.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.livenotification.delivery.domain.ChannelType;
import com.livenotification.notification.application.RegisterCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record RequestHash(String value) {
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public RequestHash {
        if (value == null || !SHA256_HEX.matcher(value).matches())
            throw new IllegalArgumentException("hash must be SHA-256 hex 64자");
    }

    /**
     * canonical-JSON hash:
     *  - top-level key order via ORDER_MAP_ENTRIES_BY_KEYS
     *  - channels array explicitly sorted (Jackson doesn't sort arrays)
     *  - payload uses canonicalized tree — same semantic, different key order → same hash
     *  - SHA-256 over UTF-8 bytes, hex-formatted
     */
    public static RequestHash of(RegisterCommand cmd, ObjectMapper mapper) {
        try {
            var sortedWriter = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

            // Build the canonical structure as a plain LinkedHashMap tree so that
            // ORDER_MAP_ENTRIES_BY_KEYS applies recursively to ALL nested map nodes,
            // including those coming from the payload ObjectNode.
            // (Jackson only sorts java.util.Map, not ObjectNode, during serialization.)
            List<String> sortedChannels = cmd.channels().stream()
                .map(ChannelType::name).sorted().toList();

            // Convert payload JsonNode → Map so nested keys are also sorted by the writer.
            Map<String, Object> payloadMap = mapper.convertValue(
                cmd.payload().value(), new TypeReference<Map<String, Object>>() {});

            // LinkedHashMap preserves insertion order; ORDER_MAP_ENTRIES_BY_KEYS will re-sort
            // all Map entries (including nested ones) during serialization.
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("channels", sortedChannels);
            root.put("event_id", cmd.eventId().value());
            root.put("payload", payloadMap);
            root.put("recipient_id", cmd.recipientId().value());
            root.put("type", cmd.type().name());

            String canonical = sortedWriter.writeValueAsString(root);

            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new RequestHash(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("hash computation failed", e);
        }
    }
}
