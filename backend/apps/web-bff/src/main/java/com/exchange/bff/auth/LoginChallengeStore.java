package com.exchange.bff.auth;

import com.exchange.common.crypto.Sha256;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A login that is halfway done: the state between "your password is right" and "your code
 * is right".
 *
 * <p>Named for the login rather than for the factor, and sitting beside {@link
 * SessionToken} and {@link SessionCookies} rather than in {@code bff/mfa}, because that is
 * what it models. Enrolling, disabling and inspecting a second factor all happen inside an
 * established session and never come near this; the second factor is merely what gates
 * this particular half-step. A future step-up challenge for, say, creating an API key
 * would need no stored state at all -- the code rides along with the request.
 *
 * <p>Login is two calls, so something has to remember which account is halfway in. Redis
 * rather than a table because the answer is worthless after a few minutes and expiring
 * itself is the entire requirement -- a row would need a sweep job to delete what a TTL
 * does for free. It is also revocable, which a signed stateless token would not be.
 *
 * <p>The token is hashed into the key, not used raw, for the same reason a session token
 * is: whoever can read the store should not come away with credentials. A short life does
 * not change that -- five minutes is plenty to use one.
 *
 * <p>Attempts are capped. Without that, a challenge is an unlimited guessing window
 * against six digits, and an attacker who has the password can simply keep trying inside
 * the TTL. At {@link #MAX_ATTEMPTS} the challenge dies and they must start from the
 * password again, which is what the login rate limiter already counts.
 */
@Component
public class LoginChallengeStore {

    /** Long enough to fetch a phone, short enough that a token left in a log or a proxy
     *  cache is useless by the time anyone reads it. */
    private static final Duration TTL = Duration.ofMinutes(5);

    private static final int MAX_ATTEMPTS = 5;

    private static final String KEY = "login-challenge:";
    private static final String ATTEMPTS_KEY = "login-challenge-attempts:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public LoginChallengeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return the raw token to hand the client; only its hash is stored */
    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), String.valueOf(userId), TTL);
        return token;
    }

    /** Whose challenge this is, or empty if it never existed, expired, or was burned. */
    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String userId = redis.opsForValue().get(key(token));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    /** Called once the code checks out, so a challenge cannot be replayed into a second
     *  session. */
    public void invalidate(String token) {
        redis.delete(key(token));
        redis.delete(attemptsKey(token));
    }

    /** Burns the challenge once too many codes have been wrong, so guessing has to go
     *  back through the password and its own rate limit. */
    public void registerFailure(String token) {
        Long attempts = redis.opsForValue().increment(attemptsKey(token));
        redis.expire(attemptsKey(token), TTL);
        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            invalidate(token);
        }
    }

    private static String key(String token) {
        return KEY + hash(token);
    }

    private static String attemptsKey(String token) {
        return ATTEMPTS_KEY + hash(token);
    }

    private static String hash(String token) {
        return HexFormat.of().formatHex(Sha256.of(token));
    }
}
