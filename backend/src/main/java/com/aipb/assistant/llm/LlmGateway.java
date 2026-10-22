package com.aipb.assistant.llm;

/**
 * One Claude call. The assistant's stages talk to this interface only, so the whole pipeline can be exercised with a
 * scripted fake; {@link AnthropicGateway} is the real implementation (official Java SDK).
 */
public interface LlmGateway {

    /** Whether calls can be made at all (an API key is configured). */
    boolean enabled();

    /** Sends one request. Throws {@link LlmException} with a presentable reason when the call fails. */
    LlmReply send(LlmRequest request);
}
