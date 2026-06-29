package com.example.BACKEND.identity.mcp;

import com.example.BACKEND.identity.session.SessionTokens;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for single-use MCP login handoff codes.
 *
 * <p>This is intentionally single-instance only (no Redis / no HA): codes live in
 * a {@link ConcurrentHashMap} and never touch disk. A code is minted by
 * {@code POST /api/auth/mcp/complete} after a successful, authenticated login and
 * redeemed exactly once by {@code POST /api/auth/mcp/exchange}.
 *
 * <p>Codes expire 60 seconds after issuance. Expired entries are evicted lazily
 * on each issue and rejected on redeem, so no scheduler is required.
 *
 * <p>This component does NOT change analytics, planners, CQM, catalogue logic,
 * warehouse execution, or any existing auth behaviour — it only brokers the
 * browser → local-process token handoff.
 */
@Component
public class McpHandoffRegistry {

    /** Single-use handoff codes expire 60 seconds after issuance. */
    private static final Duration CODE_TTL = Duration.ofSeconds(60);
    private static final int CODE_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Handoff> codes = new ConcurrentHashMap<>();

    /**
     * A pending handoff: the freshly minted MCP session tokens, the MCP-supplied
     * {@code state} (echoed back to the loopback for CSRF protection), a small
     * identity summary returned on exchange, and the expiry instant.
     */
    public record Handoff(
            SessionTokens tokens,
            String state,
            Map<String, Object> identitySummary,
            Instant expiresAt
    ) {
        boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /**
     * Mint a single-use code bound to the given session tokens and state.
     *
     * @return the opaque handoff code (high-entropy hex)
     */
    public String issue(SessionTokens tokens, String state, Map<String, Object> identitySummary) {
        evictExpired();
        String code = newCode();
        codes.put(code, new Handoff(tokens, state, identitySummary, Instant.now().plus(CODE_TTL)));
        return code;
    }

    /**
     * Redeem a code exactly once. The code is removed regardless of validity, so
     * a single code can never be exchanged twice. Returns empty if the code is
     * unknown or already expired.
     */
    public Optional<Handoff> redeem(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Handoff handoff = codes.remove(code.trim());
        if (handoff == null || handoff.isExpired(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(handoff);
    }

    private String newCode() {
        byte[] buffer = new byte[CODE_BYTES];
        random.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    private void evictExpired() {
        Instant now = Instant.now();
        codes.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }
}
