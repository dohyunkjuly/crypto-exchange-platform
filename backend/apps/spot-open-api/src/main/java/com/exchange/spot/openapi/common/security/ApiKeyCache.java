package com.exchange.spot.openapi.common.security;

import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.apikey.UserApiKeyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Keys by public id, remembered briefly so a bot's request rate is not the database's read rate.
 *
 * <p>Misses are remembered too: a flood of random ids would otherwise be a flood of indexed
 * reads. The horizon is what bounds how long a revocation takes to bite on a busy instance --
 * thirty seconds is the promise, made once here rather than tuned per caller.
 *
 * <p>A map with expiry stamps rather than a cache library: the key set is small (one row per
 * key ever made) and the semantics are two lines. Cleared wholesale if it ever grows past the
 * cap, which only an attack could cause.
 */
@Component
public class ApiKeyCache {

    static final Duration HORIZON = Duration.ofSeconds(30);
    private static final int CAP = 10_000;

    private record Entry(UserApiKey key, Instant until) {
    }

    private final UserApiKeyRepository keys;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ApiKeyCache(UserApiKeyRepository keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    public Optional<UserApiKey> find(String apiKey) {
        Instant now = clock.instant();
        Entry cached = entries.get(apiKey);
        if (cached != null && cached.until().isAfter(now)) {
            return Optional.ofNullable(cached.key());
        }
        if (entries.size() >= CAP) {
            entries.clear();
        }
        UserApiKey found = keys.findByApiKey(apiKey).orElse(null);
        entries.put(apiKey, new Entry(found, now.plus(HORIZON)));
        return Optional.ofNullable(found);
    }
}
