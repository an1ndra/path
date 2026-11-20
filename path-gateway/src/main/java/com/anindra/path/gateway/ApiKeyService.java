package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.repo.ClientKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private static final String PREFIX = "sk-gw-";
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final ClientKeyRepository keys;
    private final GatewayProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Long> lastTouch = new ConcurrentHashMap<>();

    public record CreatedKey(ClientKey key, String plaintext) {}

    @Transactional
    public CreatedKey create(String name, BigDecimal creditLimitUsd, Integer rpmLimit) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name is required", "name");
        }
        String secret = generateSecret();
        ClientKey key = new ClientKey();
        key.setName(name.trim());
        key.setKeyHash(hash(secret));
        key.setKeyPrefix(secret.substring(0, 12));
        key.setKeySuffix(secret.substring(secret.length() - 4));
        key.setCreditLimitUsd(creditLimitUsd != null ? creditLimitUsd : props.getDefaultCreditLimitUsd());
        key.setRpmLimit(rpmLimit != null ? rpmLimit : props.getDefaultRpmLimit());
        key.setCreatedAt(Instant.now());
        keys.save(key);
        return new CreatedKey(key, secret);
    }

    public Optional<ClientKey> findByRawKey(String raw) {
        return keys.findByKeyHash(hash(raw));
    }

    /** Resolve a bearer token, enforcing enabled/revoked state. */
    public ClientKey authenticate(String raw) {
        // One error for every rejection. Distinct codes for missing/invalid/disabled turned
        // this into a key-validity oracle: an attacker could tell a well-formed-but-unknown
        // key from a revoked one, and 193k guesses were evaluated against it at ~474/s.
        //
        // The lookup runs even for a missing key, so the work is the same shape whatever was
        // sent. Skipping it made "no header" measurably faster than "wrong key", which is a
        // timing oracle of its own even though the bodies are now identical.
        ClientKey key = findByRawKey(raw == null ? "" : raw.trim())
                .orElseThrow(ApiKeyService::invalidKey);
        if (!key.isEnabled() || key.getRevokedAt() != null) {
            throw invalidKey();
        }
        return key;
    }

    private static ApiException invalidKey() {
        return ApiException.unauthorized(
                "Invalid or missing API key. Send a current key as 'Authorization: Bearer sk-gw-...'.",
                "invalid_api_key");
    }

    /** Deduct spend with a row lock so parallel requests cannot lose updates. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void charge(Long keyId, BigDecimal costUsd) {
        if (costUsd == null || costUsd.signum() <= 0) {
            return;
        }
        keys.lockById(keyId).ifPresent(k -> k.setUsedUsd(k.getUsedUsd().add(costUsd)));
    }

    /** Updates last-used at most once a minute per key. */
    public void touch(Long keyId) {
        long now = System.currentTimeMillis();
        Long previous = lastTouch.put(keyId, now);
        if (previous != null && now - previous < 60_000) {
            return;
        }
        keys.findById(keyId).ifPresent(k -> {
            k.setLastUsedAt(Instant.now());
            keys.save(k);
        });
    }

    /** @return true when the reservation was granted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(Long keyId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return true;
        }
        return keys.reserve(keyId, amount, Instant.now()) > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long keyId, BigDecimal amount) {
        if (amount != null && amount.signum() > 0) {
            keys.release(keyId, amount);
        }
    }

    /** Frees reservations left behind by a crashed instance. */
    @Scheduled(fixedDelay = 300_000)
    @Transactional
    void releaseStaleReservations() {
        int released = keys.releaseStale(Instant.now().minusSeconds(900));
        if (released > 0) {
            log.info("Released stale budget reservations on {} key(s)", released);
        }
    }

    public BigDecimal remaining(ClientKey key) {
        if (key.getCreditLimitUsd() == null) {
            return null;
        }
        BigDecimal reserved = key.getReservedUsd() == null ? BigDecimal.ZERO : key.getReservedUsd();
        return key.getCreditLimitUsd().subtract(key.getUsedUsd()).subtract(reserved);
    }

    public static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generateSecret() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < 40; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
