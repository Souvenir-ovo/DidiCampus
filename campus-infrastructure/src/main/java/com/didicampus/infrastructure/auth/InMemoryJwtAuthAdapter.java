package com.didicampus.infrastructure.auth;

import com.didicampus.domain.auth.ports.RefreshTokenPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(name = "didicampus.auth.mode", havingValue = "jwt", matchIfMissing = true)
public class InMemoryJwtAuthAdapter implements RefreshTokenPort {

    private final SecretKey secretKey;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final ConcurrentMap<String, Instant> revokedAccessTokens = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RefreshSession> refreshSessions = new ConcurrentHashMap<>();

    public InMemoryJwtAuthAdapter(
            @Value("${didicampus.auth.jwt-secret:didi-campus-local-demo-secret-key-min-32-bytes}") String secret,
            @Value("${didicampus.auth.access-token-minutes:120}") long accessMinutes,
            @Value("${didicampus.auth.refresh-token-minutes:10080}") long refreshMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(Math.max(1L, accessMinutes));
        this.refreshTtl = Duration.ofMinutes(Math.max(1L, refreshMinutes));
    }

    @Override
    public String login(long userId) {
        return createAccessToken(userId);
    }

    @Override
    public TokenPair loginWithRefresh(long userId) {
        return new TokenPair(createAccessToken(userId), createRefreshToken(userId));
    }

    @Override
    public Optional<TokenPair> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        RefreshSession session = refreshSessions.remove(refreshToken);
        if (session == null || session.expired()) {
            return Optional.empty();
        }
        return Optional.of(new TokenPair(createAccessToken(session.userId()), createRefreshToken(session.userId())));
    }

    @Override
    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String tokenId = claims.getId();
            Instant revokedUntil = tokenId == null ? null : revokedAccessTokens.get(tokenId);
            if (revokedUntil != null && revokedUntil.isAfter(Instant.now())) {
                return Optional.empty();
            }
            if (revokedUntil != null) {
                revokedAccessTokens.remove(tokenId, revokedUntil);
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
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String tokenId = claims.getId();
            Date expiresAt = claims.getExpiration();
            if (tokenId != null && expiresAt != null && expiresAt.toInstant().isAfter(Instant.now())) {
                revokedAccessTokens.put(tokenId, expiresAt.toInstant());
            }
        } catch (JwtException | IllegalArgumentException ignored) {
            // 无效 token 不需要再加入黑名单。
        }
    }

    @Override
    public void revokeRefresh(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshSessions.remove(refreshToken);
        }
    }

    private String createAccessToken(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(secretKey)
                .compact();
    }

    private String createRefreshToken(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        refreshSessions.put(token, new RefreshSession(userId, Instant.now().plus(refreshTtl)));
        return token;
    }

    private record RefreshSession(long userId, Instant expiresAt) {
        private boolean expired() {
            return !expiresAt.isAfter(Instant.now());
        }
    }
}
