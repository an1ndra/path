package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.UsageRecord;
import com.anindra.path.repo.ClientKeyRepository;
import com.anindra.path.repo.UsageRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminStatsSeriesTest {

    private final UsageRecordRepository usage = Mockito.mock(UsageRecordRepository.class);
    private final AdminService adminService = Mockito.mock(AdminService.class);
    private final ClientKeyRepository keys = Mockito.mock(ClientKeyRepository.class);
    private final AdminReportingService reporting = new AdminReportingService(usage, adminService, keys);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> emptyStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", 0);
        return out;
    }

    private static Object[] row(Instant at, UsageRecord.Status status, String cost, Long latencyMs) {
        return new Object[]{at, status, new BigDecimal(cost), latencyMs};
    }

    private static List<Object[]> rows(Object[]... rows) {
        return List.of(rows);
    }

    @Test
    void a24hWindowInHourlyBucketsHas24PointsIncludingEmptyOnes() {
        when(adminService.stats(any())).thenReturn(emptyStats());
        Instant now = Instant.now();
        when(usage.findSeriesSource(any(), any())).thenReturn(rows(
                row(now.minusSeconds(30), UsageRecord.Status.SUCCESS, "0.25", 100L),
                row(now.minusSeconds(3_600), UsageRecord.Status.SUCCESS, "0.50", 300L),
                row(now.minusSeconds(3_600), UsageRecord.Status.ERROR, "0", 0L)));

        Map<String, Object> out = reporting.stats("24h", null, "1h");

        List<Map<String, Object>> series = (List<Map<String, Object>>) out.get("series");
        assertThat(series).hasSize(24);
        long requests = series.stream().mapToLong(p -> ((Number) p.get("requests")).longValue()).sum();
        long failures = series.stream().mapToLong(p -> ((Number) p.get("failures")).longValue()).sum();
        BigDecimal cost = series.stream()
                .map(p -> (BigDecimal) p.get("cost_usd"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(requests).isEqualTo(3);
        assertThat(failures).isEqualTo(1);
        assertThat(cost).isEqualByComparingTo("0.75");
        // Empty buckets are present and zeroed rather than omitted.
        assertThat(series).anyMatch(p -> ((Number) p.get("requests")).longValue() == 0
                && ((BigDecimal) p.get("cost_usd")).signum() == 0);
        assertThat(series).allMatch(p -> p.containsKey("from") && p.containsKey("p95_latency_ms"));
        // The newest point is the one containing "now".
        assertThat(((Number) series.get(series.size() - 1).get("requests")).longValue()).isEqualTo(1L);
    }

    @Test
    void theWholeWindowIsReadInOneQuery() {
        when(adminService.stats(any())).thenReturn(emptyStats());
        Instant now = Instant.now();
        when(usage.findSeriesSource(any(), any())).thenReturn(rows(
                row(now, UsageRecord.Status.SUCCESS, "0.1", 10L)));

        reporting.stats("6h", null, "5m");

        verify(usage, times(1)).findSeriesSource(any(), any());
    }

    @Test
    void aProjectSeriesReadsItsKeysInOneQuery() {
        when(adminService.stats(any())).thenReturn(emptyStats());
        com.anindra.path.domain.ClientKey key = Mockito.mock(com.anindra.path.domain.ClientKey.class);
        when(key.getId()).thenReturn(1L);
        when(keys.findByProjectIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(key));
        when(usage.findByClientKeyIdInAndCreatedAtAfterOrderByCreatedAtDesc(anyCollection(), any(), any()))
                .thenReturn(List.of());
        when(usage.findSeriesSourceForKeys(anyCollection(), any(), any())).thenReturn(List.of());

        Map<String, Object> out = reporting.stats("24h", 7L, "1h");

        assertThat((List<?>) out.get("series")).hasSize(24);
        verify(usage, times(1)).findSeriesSourceForKeys(anyCollection(), any(), any());
    }

    @Test
    void anInvalidBucketIsA400ListingTheAcceptedValues() {
        assertThatThrownBy(() -> reporting.stats("24h", null, "3h"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(400);
                    assertThat(api.getMessage()).contains("1m").contains("5m").contains("1h").contains("1d");
                });
    }

    @Test
    void omittingTheBucketKeepsTodaysResponseUnchanged() {
        when(adminService.stats(any())).thenReturn(emptyStats());

        Map<String, Object> out = reporting.stats("24h", null);

        assertThat(out).doesNotContainKeys("series", "bucket");
        assertThat(out.get("requests")).isEqualTo(0);
    }

    @Test
    void aHugeWindowIsClampedTo500Points() {
        when(adminService.stats(any())).thenReturn(emptyStats());
        when(usage.findSeriesSource(any(), any())).thenReturn(new ArrayList<>());

        Map<String, Object> out = reporting.stats("365d", null, "1h");

        assertThat((List<?>) out.get("series")).hasSize(500);
    }
}
