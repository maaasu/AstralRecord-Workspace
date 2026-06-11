package io.github.maaasu.astralRecord.feature.market.repository;

import java.time.Duration;
import java.time.Instant;

record MarketCacheEntry<T>(T value, Instant expiresAt) {
    static <T> MarketCacheEntry<T> of(T value, Duration ttl) {
        return new MarketCacheEntry<>(value, Instant.now().plus(ttl));
    }

    boolean isAlive() {
        return Instant.now().isBefore(expiresAt);
    }
}
