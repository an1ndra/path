package com.anindra.path.domain;

public enum ProviderType {
    /** OpenAI chat-completions wire format (OpenAI, Groq, Together, vLLM, ...). */
    OPENAI,
    /** Anthropic Messages API wire format. */
    ANTHROPIC
}
