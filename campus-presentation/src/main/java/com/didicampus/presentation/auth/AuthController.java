package com.didicampus.presentation.auth;

import com.didicampus.domain.auth.ports.AuthPort;
import com.didicampus.domain.auth.ports.RefreshTokenPort;
import com.didicampus.presentation.api.ApiResponse;
import com.didicampus.shared.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthPort authPort;

    public AuthController(AuthPort authPort) {
        this.authPort = authPort;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        if (authPort instanceof RefreshTokenPort refreshTokenPort) {
            RefreshTokenPort.TokenPair pair = refreshTokenPort.loginWithRefresh(request.userId());
            return ApiResponse.ok(tokenBody(request.userId(), pair.accessToken(), pair.refreshToken()));
        }
        String accessToken = authPort.login(request.userId());
        return ApiResponse.ok(tokenBody(request.userId(), accessToken, null));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh(@Valid @RequestBody RefreshRequest request) {
        if (!(authPort instanceof RefreshTokenPort refreshTokenPort)) {
            return ApiResponse.fail(ErrorCode.UNAUTHORIZED.name(), ErrorCode.UNAUTHORIZED.message());
        }
        return refreshTokenPort.refresh(request.refreshToken())
                .map(pair -> ApiResponse.ok(tokenBody(null, pair.accessToken(), pair.refreshToken())))
                .orElseGet(() -> ApiResponse.fail(ErrorCode.UNAUTHORIZED.name(), ErrorCode.UNAUTHORIZED.message()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody(required = false) RefreshRequest request) {
        String accessToken = bearerToken(authorization);
        if (accessToken != null) {
            authPort.logout(accessToken);
        }
        if (request != null && authPort instanceof RefreshTokenPort refreshTokenPort) {
            refreshTokenPort.revokeRefresh(request.refreshToken());
        }
        return ApiResponse.ok();
    }

    private Map<String, Object> tokenBody(Long userId, String accessToken, String refreshToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", accessToken);
        body.put("accessToken", accessToken);
        if (refreshToken != null) {
            body.put("refreshToken", refreshToken);
        }
        if (userId != null) {
            body.put("userId", userId);
        }
        return body;
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring(7).trim();
    }

    public record LoginRequest(@NotNull Long userId) {
    }

    public record RefreshRequest(String refreshToken) {
    }
}
