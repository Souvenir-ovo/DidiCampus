package com.didicampus.infrastructure.auth;

import com.didicampus.domain.auth.ports.RefreshTokenPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed JWT adapter.
 *
 * JWT 本身保持无状态，但 refresh token 和注销黑名单必须跨实例共享，
 * 因此将这两类短期状态放入 Redis。默认配置仍使用内存适配器。
 */
@Component
@ConditionalOnProperty(name = "didicampus.auth.mode", havingValue = "redis-jwt")
public class RedisJwtAuthAdapter implements RefreshTokenPort {

    private static final Logger log = LoggerFactory.getLogger(RedisJwtAuthAdapter.class);
    private static final String REFRESH_KEY_PREFIX = "auth:jwt:refresh:";
    private static final String REVOKED_KEY_PREFIX = "auth:jwt:revoked:";

    private final StringRedisTemplate redis;
    private final SecretKey signingKey;
    private final Duration accessLifetime;
    private final Duration refreshLifetime;

    public RedisJwtAuthAdapter(
            StringRedisTemplate redis,
            @Value("${didicampus.auth.jwt-secret:didi-campus-local-demo-secret-key-min-32-bytes}") String secret,
            @Value("${didicampus.auth.access-token-minutes:120}") long accessMinutes,
            @Value("${didicampus.auth.refresh-token-minutes:10080}") long refreshMinutes) {
        this.redis = redis;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessLifetime = Duration.ofMinutes(Math.max(1L, accessMinutes));
        this.refreshLifetime = Duration.ofMinutes(Math.max(1L, refreshMinutes));
    }

    @Override
    public String login(long userId) {
        return issueAccessToken(userId);
    }

    @Override
    public TokenPair loginWithRefresh(long userId) {
        return new TokenPair(issueAccessToken(userId), issueRefreshToken(userId));
    }

    @Override
    public Optional<TokenPair> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }

        String redisKey = REFRESH_KEY_PREFIX + refreshToken;
        String userIdValue = redis.opsForValue().get(redisKey);
        if (userIdValue == null) {
            return Optional.empty();
        }

        redis.delete(redisKey);
        try {
            long userId = Long.parseLong(userIdValue);
            return Optional.of(new TokenPair(issueAccessToken(userId), issueRefreshToken(userId)));
        } catch (NumberFormatException ex) {
            log.warn("invalid refresh token payload, key={}", redisKey);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String tokenId = claims.getId();
            if (tokenId != null && Boolean.TRUE.equals(redis.hasKey(REVOKED_KEY_PREFIX + tokenId))) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String tokenId = claims.getId();
            Date expiresAt = claims.getExpiration();
            if (tokenId == null || expiresAt == null) {
                return;
            }

            long remainingMillis = expiresAt.getTime() - System.currentTimeMillis();
            if (remainingMillis > 0) {
                redis.opsForValue().set(
                        REVOKED_KEY_PREFIX + tokenId,
                        "1",
                        Duration.ofMillis(remainingMillis));
            }
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("ignore logout for invalid access token");
        }
    }

    @Override
    public void revokeRefresh(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            redis.delete(REFRESH_KEY_PREFIX + refreshToken);
        }
    }

    private String issueAccessToken(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessLifetime)))
                .signWith(signingKey)
                .compact();
    }

    private String issueRefreshToken(long userId) {
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(
                REFRESH_KEY_PREFIX + refreshToken,
                String.valueOf(userId),
                refreshLifetime);
        return refreshToken;
    }
}
