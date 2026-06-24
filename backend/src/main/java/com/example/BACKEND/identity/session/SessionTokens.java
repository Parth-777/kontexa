package com.example.BACKEND.identity.session;

import java.time.LocalDateTime;
import java.util.UUID;

public record SessionTokens(
        UUID sessionId,
        String accessToken,
        String refreshToken,
        LocalDateTime accessExpiresAt,
        LocalDateTime refreshExpiresAt
) {}
