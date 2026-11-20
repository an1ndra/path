package com.anindra.path.domain;

public enum ProviderType {
    /** OpenAI chat-completions wire format (OpenAI, Groq, Together, vLLM, ...). */
    OPENAI,
    /** Anthropic Messages API wire format. */
    ANTHROPIC,
    /** Google Gemini native generateContent (AI Studio). */
    GEMINI,
    /** Vertex AI generateContent; credentials are a service-account JSON, not an API key. */
    VERTEX
}
