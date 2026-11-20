package com.anindra.path.gateway;

/**
 * Token accounting for one call, normalised across providers.
 *
 * {@code promptTokens} is the <em>total</em> prompt size, including cached and
 * cache-write tokens, so that:
 * <pre>
 *   billableInput = promptTokens - cachedInputTokens - cacheWriteTokens
 * </pre>
 * OpenAI reports cached tokens as a subset of {@code prompt_tokens}; Anthropic
 * reports them separately, so the adapter sums them into {@code promptTokens}.
 */
public record TokenUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer cachedInputTokens,
        Integer cacheWriteTokens,
        Integer reasoningTokens,
        Integer audioInputTokens,
        Integer audioOutputTokens,
        boolean estimated) {

    public static final TokenUsage EMPTY =
            new TokenUsage(null, null, null, null, null, null, null, false);

    public static TokenUsage of(Integer prompt, Integer completion) {
        return new TokenUsage(prompt, completion, 0, 0, 0, 0, 0, false);
    }

    /** Used when the upstream reported nothing: counts are inferred, not observed. */
    public static TokenUsage estimated(int prompt, int completion) {
        return new TokenUsage(prompt, completion, 0, 0, 0, 0, 0, true);
    }

    public static TokenUsage merge(TokenUsage base, TokenUsage update) {
        if (update == null || !update.reported()) {
            return base;
        }
        return new TokenUsage(
                update.promptTokens != null ? update.promptTokens : base.promptTokens,
                update.completionTokens != null ? update.completionTokens : base.completionTokens,
                update.cachedInputTokens != null ? update.cachedInputTokens : base.cachedInputTokens,
                update.cacheWriteTokens != null ? update.cacheWriteTokens : base.cacheWriteTokens,
                update.reasoningTokens != null ? update.reasoningTokens : base.reasoningTokens,
                update.audioInputTokens != null ? update.audioInputTokens : base.audioInputTokens,
                update.audioOutputTokens != null ? update.audioOutputTokens : base.audioOutputTokens,
                base.estimated && update.estimated);
    }

    public boolean reported() {
        return promptTokens != null || completionTokens != null;
    }

    public int prompt() { return nz(promptTokens); }
    public int completion() { return nz(completionTokens); }
    public int cached() { return Math.min(nz(cachedInputTokens), prompt()); }
    public int cacheWrite() { return Math.min(nz(cacheWriteTokens), prompt()); }
    public int reasoning() { return nz(reasoningTokens); }
    public int audioInput() { return nz(audioInputTokens); }
    public int audioOutput() { return nz(audioOutputTokens); }
    public int total() { return prompt() + completion(); }

    /** Tokens charged at the plain input rate. */
    public int billableInput() {
        return Math.max(0, prompt() - cached() - cacheWrite());
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
