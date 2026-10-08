package com.didicampus.domain.auth.ports;

import java.util.Optional;

public interface RefreshTokenPort extends AuthPort {

    TokenPair loginWithRefresh(long userId);

    Optional<TokenPair> refresh(String refreshToken);

    void revokeRefresh(String refreshToken);

    record TokenPair(String accessToken, String refreshToken) {}
}
