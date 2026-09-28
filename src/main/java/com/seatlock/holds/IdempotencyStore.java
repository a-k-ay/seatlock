package com.seatlock.holds;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private static final String KEY_PREFIX = "idempotency:holds:";
    private static final String SEPARATOR = "||";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public String hash(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Optional<Cached> get(String idempotencyKey) {
        String stored = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (stored == null) return Optional.empty();
        int sepIdx = stored.indexOf(SEPARATOR);
        if (sepIdx < 0) return Optional.empty();
        return Optional.of(new Cached(
                stored.substring(0, sepIdx),
                stored.substring(sepIdx + SEPARATOR.length())
        ));
    }

    public void store(String idempotencyKey, String requestHash, String responseJson) {
        String value = requestHash + SEPARATOR + responseJson;
        redis.opsForValue().set(KEY_PREFIX + idempotencyKey, value, TTL);
    }

    public record Cached(String requestHash, String responseJson) {}
}