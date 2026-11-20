package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.IdempotencyRecord;
import com.anindra.path.repo.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Idempotent replay for chat completions.
 *
 * The first request with a given {@code Idempotency-Key} writes a PENDING claim and runs;
 * a repeat with the same body replays the stored response, a repeat with a different body
 * is rejected, and a concurrent duplicate gets 409 rather than a second upstream call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    public record Replay(int httpStatus, String contentType, String body) {
    }

    private final IdempotencyRecordRepository records;
    private final GatewayProperties props;

    /**
     * @return the stored response when this key was already completed, empty when the
     *         caller should proceed (a PENDING claim has been written).
     */
    @Transactional
    public Optional<Replay> begin(Long clientKeyId, String rawKey, String requestHash) {
        String key = ApiKeyService.hash(rawKey);
        Optional<IdempotencyRecord> existing = records.findByClientKeyIdAndIdempotencyKey(clientKeyId, key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getStatus() == IdempotencyRecord.Status.PENDING) {
                java.time.Duration staleAfter = props.getIdempotency().getPendingTimeout();
                boolean abandoned = record.getCreatedAt().isBefore(Instant.now().minus(staleAfter));
                if (!abandoned) {
                    throw new ApiException(409, "invalid_request_error", "idempotency_in_progress",
                            "A request with this Idempotency-Key is still in progress.");
                }
                // The instance that claimed this key died mid-request; let the retry run.
                log.warn("Reclaiming stale PENDING idempotency claim {}", record.getId());
                records.delete(record);
                records.flush();
            } else {
                if (!record.getRequestHash().equals(requestHash)) {
                    throw new ApiException(409, "invalid_request_error", "idempotency_conflict",
                            "This Idempotency-Key was already used with a different request body.");
                }
                return Optional.of(new Replay(record.getHttpStatus(), record.getContentType(), record.getBody()));
            }
        }

        IdempotencyRecord claim = new IdempotencyRecord();
        claim.setClientKeyId(clientKeyId);
        claim.setIdempotencyKey(key);
        claim.setRequestHash(requestHash);
        claim.setStatus(IdempotencyRecord.Status.PENDING);
        claim.setExpiresAt(Instant.now().plus(props.getIdempotency().getTtl()));
        try {
            records.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(409, "invalid_request_error", "idempotency_in_progress",
                    "A request with this Idempotency-Key is still in progress.");
        }
        return Optional.empty();
    }

    @Transactional
    public void complete(Long clientKeyId, String rawKey, int httpStatus, String contentType, String body) {
        records.findByClientKeyIdAndIdempotencyKey(clientKeyId, ApiKeyService.hash(rawKey))
                .ifPresent(record -> {
                    record.setStatus(IdempotencyRecord.Status.COMPLETED);
                    record.setHttpStatus(httpStatus);
                    record.setContentType(contentType);
                    record.setBody(body);
                });
    }

    @Transactional
    public void abandon(Long clientKeyId, String rawKey) {
        records.findByClientKeyIdAndIdempotencyKey(clientKeyId, ApiKeyService.hash(rawKey))
                .ifPresent(records::delete);
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    void purgeExpired() {
        long removed = records.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired idempotency records", removed);
        }
    }
}
