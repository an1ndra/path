package com.anindra.path.repo;

import com.anindra.path.domain.UsageRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    List<UsageRecord> findByClientKeyIdOrderByCreatedAtDesc(Long clientKeyId, Pageable pageable);

    List<UsageRecord> findByCreatedAtAfterOrderByCreatedAtDesc(Instant from, Pageable pageable);

    List<UsageRecord> findByCreatedAtBetweenOrderByCreatedAtAsc(Instant from, Instant to, Pageable pageable);

    List<UsageRecord> findByClientKeyIdInAndCreatedAtAfterOrderByCreatedAtDesc(
            java.util.Collection<Long> keyIds, Instant from, Pageable pageable);

    List<UsageRecord> findByClientKeyIdInAndCreatedAtBetweenOrderByCreatedAtAsc(
            java.util.Collection<Long> keyIds, Instant from, Instant to, Pageable pageable);

    long countByCreatedAtAfter(Instant from);

    long countByCreatedAtAfterAndCacheTier(Instant from, String cacheTier);

    long countByClientKeyIdInAndCreatedAtAfterAndCacheTier(
            java.util.Collection<Long> keyIds, Instant from, String cacheTier);

    long deleteByCreatedAtBefore(Instant cutoff);

    long countByClientKeyIdAndCreatedAtAfter(Long clientKeyId, Instant from);

    @Query("select coalesce(sum(u.costUsd), 0) from UsageRecord u "
            + "where u.clientKeyId in (select k.id from ClientKey k where k.projectId = :projectId) "
            + "and u.createdAt >= :from")
    BigDecimal sumCostForProjectSince(Long projectId, Instant from);

    @Query("select coalesce(sum(u.costUsd), 0) from UsageRecord u where u.createdAt >= :from")
    BigDecimal sumCostSince(Instant from);

    @Query("select coalesce(sum(u.costUsd), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    BigDecimal sumCostForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.costUsd), 0) from UsageRecord u "
            + "where u.clientKeyId = :keyId and u.endUser = :endUser and u.createdAt >= :from")
    BigDecimal sumCostForKeyAndEndUserSince(Long keyId, String endUser, Instant from);

    @Query("select u.endUser, count(u), coalesce(sum(u.totalTokens), 0), coalesce(sum(u.costUsd), 0) "
            + "from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from "
            + "and u.endUser is not null group by u.endUser order by sum(u.costUsd) desc")
    List<Object[]> groupByEndUserSince(Long keyId, Instant from);

    List<UsageRecord> findByClientKeyIdAndEndUserOrderByCreatedAtDesc(
            Long clientKeyId, String endUser, Pageable pageable);

    @Query("select coalesce(sum(u.promptTokens), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    Long sumPromptTokensForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.completionTokens), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    Long sumCompletionTokensForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.totalTokens), 0) from UsageRecord u where u.createdAt >= :from")
    Long sumTokensSince(Instant from);

    @Query("select u.latencyMs from UsageRecord u where u.createdAt >= :from and u.status = com.anindra.path.domain.UsageRecord.Status.SUCCESS")
    List<Long> findLatenciesSince(Instant from);

    /**
     * Every row a time-series bucket needs, in one query. The caller groups by time in Java
     * so the same JPQL works on H2 and Postgres without database-specific date arithmetic.
     */
    @Query("select u.createdAt, u.status, u.costUsd, u.latencyMs from UsageRecord u "
            + "where u.createdAt >= :from and u.createdAt < :to")
    List<Object[]> findSeriesSource(Instant from, Instant to);

    @Query("select u.createdAt, u.status, u.costUsd, u.latencyMs from UsageRecord u "
            + "where u.clientKeyId in :keyIds and u.createdAt >= :from and u.createdAt < :to")
    List<Object[]> findSeriesSourceForKeys(java.util.Collection<Long> keyIds, Instant from, Instant to);


    @Query("select u.providerSlug, count(u), coalesce(sum(u.costUsd), 0) from UsageRecord u where u.createdAt >= :from group by u.providerSlug")
    List<Object[]> groupByProviderSince(Instant from);

    @Query("select u.publicModelId, count(u), coalesce(sum(u.totalTokens), 0), coalesce(sum(u.costUsd), 0) from UsageRecord u where u.createdAt >= :from group by u.publicModelId order by count(u) desc")
    List<Object[]> groupByModelSince(Instant from);

    @Query("select coalesce(sum(u.promptTokens), 0) from UsageRecord u where u.createdAt >= :from")
    Long sumPromptTokensSince(Instant from);

    @Query("select coalesce(sum(u.cachedInputTokens), 0) from UsageRecord u where u.createdAt >= :from")
    Long sumCachedTokensSince(Instant from);

    @Query("select coalesce(sum(u.cachedSavingsUsd), 0) from UsageRecord u where u.createdAt >= :from")
    BigDecimal sumCachedSavingsSince(Instant from);

    @Query("select u.ttftMs from UsageRecord u where u.createdAt >= :from and u.ttftMs is not null")
    List<Long> findTtftSince(Instant from);

    @Query("select u.tokensPerSecond from UsageRecord u where u.createdAt >= :from and u.tokensPerSecond is not null")
    List<BigDecimal> findThroughputSince(Instant from);

    long countByAttemptsGreaterThanAndCreatedAtAfter(int attempts, Instant from);

    long countByUsageEstimatedTrueAndCreatedAtAfter(Instant from);

    // ------------------------------------------------------------ per client key

    @Query("select coalesce(sum(u.cachedInputTokens), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    Long sumCachedTokensForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.reasoningTokens), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    Long sumReasoningTokensForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.cachedSavingsUsd), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    BigDecimal sumCachedSavingsForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.inputCostUsd), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    BigDecimal sumInputCostForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.cachedInputCostUsd), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    BigDecimal sumCachedCostForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.cacheWriteCostUsd), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    BigDecimal sumCacheWriteCostForKeySince(Long keyId, Instant from);

    @Query("select coalesce(sum(u.outputCostUsd), 0) from UsageRecord u where u.clientKeyId = :keyId and u.createdAt >= :from")
    BigDecimal sumOutputCostForKeySince(Long keyId, Instant from);
}
