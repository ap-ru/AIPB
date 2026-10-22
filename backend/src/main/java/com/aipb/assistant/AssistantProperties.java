package com.aipb.assistant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** aipb.assistant.* - model, per-stage effort and token limits, and the caps that bound every run. */
@Data
@Component
@ConfigurationProperties("aipb.assistant")
public class AssistantProperties {
    private String model = "claude-opus-5-5";
    /** Effort per stage (low | medium | high | xhigh | max). */
    private Map<String, String> effort = new LinkedHashMap<>(Map.of("intake", "medium", "build", "medium", "review", "medium", "present", "medium"));
    private Map<String, Long> maxTokens = new LinkedHashMap<>(Map.of("intake", 8000L, "build", 32000L, "review", 32000L, "present", 8000L));
    private int maxToolCalls = 16;
    private int maxSubmits = 4;
    private int maxPresentAttempts = 3;
    private int runTimeoutSeconds = 360;
    /** USD per million tokens, for the per-session cost estimate. */
    private Price price = new Price();

    @Data
    public static class Price {
        private double input = 4.0;
        private double output = 20.0;
        private double cacheRead = 0.2;
        private double cacheWrite = 5.0;

        public double cost(long in, long out, long read, long write) {
            return (in * input + out * output + read * cacheRead + write * cacheWrite) / 1e6;
        }
    }

    public String effort(String stage) { return effort.getOrDefault(stage, "medium"); }

    public long maxTokens(String stage) { return maxTokens.getOrDefault(stage, 8000L); }
}
