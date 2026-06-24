package com.example.BACKEND.identity.session;

import java.time.LocalDateTime;
import java.util.UUID;

public record AuthSessionRecord(
        UUID id,
        UUID identityId,
        UUID activeWorkspaceId,
        String accessTokenHash,
        String refreshTokenHash,
        String deviceLabel,
        String userAgent,
        String ipAddress,
        LocalDateTime createdAt,
        LocalDateTime accessExpiresAt,
        LocalDateTime refreshExpiresAt,
        LocalDateTime revokedAt,
        LocalDateTime lastUsedAt
) {}
