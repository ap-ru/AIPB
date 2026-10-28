package com.aipb.assistant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** aipb.assistant.* - per-stage model, effort and token limits, and the caps that bound every run. */
@Data
@Component
@ConfigurationProperties("aipb.assistant")
public class AssistantProperties {
    public static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    /** Model per stage: the light stages (intake, present) on Haiku, the reasoning-heavy ones (build, review) on Sonnet. */
    private Map<String, String> model = new LinkedHashMap<>(Map.of("intake", "claude-haiku-5-5", "build", "claude-sonnet-5-5", "review", "claude-sonnet-5-5", "present", "claude-haiku-5-5",
            "narrate", "claude-haiku-5-5"));
    /** Effort per stage (low | medium | high | xhigh | max). */
    private Map<String, String> effort = new LinkedHashMap<>(Map.of("intake", "medium", "build", "medium", "review", "medium", "present", "medium", "narrate", "medium"));
    private Map<String, Long> maxTokens = new LinkedHashMap<>(Map.of("intake", 8000L, "build", 32000L, "review", 32000L, "present", 8000L, "narrate", 8000L));
    private int maxToolCalls = 16;
    private int maxSubmits = 4;
    private int maxPresentAttempts = 3;
    private int runTimeoutSeconds = 360;
    /** USD per million tokens by model id, for the per-session cost estimate. */
    private Map<String, Price> price = new LinkedHashMap<>(Map.of(
            "claude-haiku-5-5", new Price(0.10, 0.50, 0.01, 0.125),
            "claude-sonnet-5-5", new Price(2.0, 10.0, 0.2, 2.5),
            "claude-opus-5-5", new Price(4.0, 20.0, 0.2, 5.0)));

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Price {
        private double input;
        private double output;
        private double cacheRead;
        private double cacheWrite;

        public double cost(long in, long out, long read, long write) {
            return (in * input + out * output + read * cacheRead + write * cacheWrite) / 1e6;
        }
    }

    public String model(String stage) { return model.getOrDefault(stage, DEFAULT_MODEL); }

    /** A model with no listed price is costed at Opus rates, so the estimate errs high rather than reading as free. */
    public Price price(String model) { return price.getOrDefault(model, price.getOrDefault("claude-opus-5-5", new Price(4.0, 20.0, 0.2, 5.0))); }

    public String effort(String stage) { return effort.getOrDefault(stage, "medium"); }

    public long maxTokens(String stage) { return maxTokens.getOrDefault(stage, 8000L); }
}
