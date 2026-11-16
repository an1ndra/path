package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anindra.path.domain.ModelRoute;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UsageCostTest {

    private final UsageService service = new UsageService(null, null);
    private final ObjectMapper mapper = new ObjectMapper();

    private static ModelRoute route(String input, String cached, String output, String write) {
        ModelRoute route = new ModelRoute("test-model", 1L, "upstream-model",
                new BigDecimal(input), new BigDecimal(output), 10);
        if (cached != null) {
            route.setCachedInputPricePerMillion(new BigDecimal(cached));
        }
        if (write != null) {
            route.setCacheWritePricePerMillion(new BigDecimal(write));
        }
        return route;
    }

    @Test
    void openAiStyleCacheDiscountIsApplied() {
        // 500 uncached input @1.00, 500 cached @0.50, 200 output @2.00 (per million)
        TokenUsage usage = new TokenUsage(1000, 200, 500, 0, 0, 0, 0, false);
        UsageService.CostBreakdown cost = service.cost(route("1.00", "0.50", "2.00", null), usage);

        assertThat(cost.inputUsd()).isEqualByComparingTo("0.00050000");
        assertThat(cost.cachedInputUsd()).isEqualByComparingTo("0.00025000");
        assertThat(cost.outputUsd()).isEqualByComparingTo("0.00040000");
        assertThat(cost.totalUsd()).isEqualByComparingTo("0.00115000");
        assertThat(cost.cachedSavingsUsd()).isEqualByComparingTo("0.00025000");
    }

    @Test
    void anthropicStyleCacheReadAndWriteArePricedSeparately() {
        // prompt 1000 = 700 uncached + 200 cache read + 100 cache write, output 300
        TokenUsage usage = new TokenUsage(1000, 300, 200, 100, 0, 0, 0, false);
        UsageService.CostBreakdown cost = service.cost(route("3.00", "0.30", "15.00", "3.75"), usage);

        assertThat(cost.inputUsd()).isEqualByComparingTo("0.00210000");
        assertThat(cost.cachedInputUsd()).isEqualByComparingTo("0.00006000");
        assertThat(cost.cacheWriteUsd()).isEqualByComparingTo("0.00037500");
        assertThat(cost.outputUsd()).isEqualByComparingTo("0.00450000");
        assertThat(cost.totalUsd()).isEqualByComparingTo("0.00703500");
    }

    @Test
    void missingCachePriceFallsBackToTheInputRate() {
        TokenUsage usage = new TokenUsage(1000, 0, 400, 0, 0, 0, 0, false);
        UsageService.CostBreakdown cost = service.cost(route("1.00", null, "1.00", null), usage);

        assertThat(cost.totalUsd()).isEqualByComparingTo("0.00100000");
        assertThat(cost.cachedSavingsUsd()).isEqualByComparingTo("0.00000000");
    }

    @Test
    void reasoningTokensAreBilledAtTheOutputRate() {
        TokenUsage usage = new TokenUsage(0, 300, 0, 0, 120, 0, 0, false);
        UsageService.CostBreakdown cost = service.cost(route("1.00", null, "10.00", null), usage);
        assertThat(cost.outputUsd()).isEqualByComparingTo("0.00300000");
        assertThat(usage.reasoning()).isEqualTo(120);
    }

    @Test
    void openAiUsageParsesCacheAndReasoningDetails() throws Exception {
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(mapper);
        JsonNode upstream = mapper.readTree("""
                {"usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120,
                           "prompt_tokens_details": {"cached_tokens": 40},
                           "completion_tokens_details": {"reasoning_tokens": 5}}}
                """);

        TokenUsage usage = adapter.parseUsage(upstream);

        assertThat(usage.prompt()).isEqualTo(100);
        assertThat(usage.completion()).isEqualTo(20);
        assertThat(usage.cached()).isEqualTo(40);
        assertThat(usage.reasoning()).isEqualTo(5);
        assertThat(usage.billableInput()).isEqualTo(60);
        assertThat(usage.estimated()).isFalse();
    }

    @Test
    void missingUsageIsReportedAsEmpty() throws Exception {
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(mapper);
        TokenUsage usage = adapter.parseUsage(mapper.readTree("{\"choices\":[]}"));
        assertThat(usage.reported()).isFalse();
    }
}
