package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantProperties;
import com.aipb.assistant.AssistantSession;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.llm.LlmReply;

import java.time.Instant;
import java.util.List;

/** Records one stage run for the audit trail: model, effort, tokens, cost, tool calls, and what context was given. */
public class RunRecorder {

    private final AssistantSession.StageRun run = new AssistantSession.StageRun();
    private final AssistantProperties props;
    private final long start = System.currentTimeMillis();
    private LlmReply.Usage usage = LlmReply.Usage.NONE;

    public RunRecorder(String stage, AssistantProperties props) {
        this.props = props;
        run.setStage(stage);
        run.setModel(props.getModel());
        run.setEffort(props.effort(stage));
        run.setStartedAt(Instant.now());
    }

    public void context(List<ContextAssembler.ManifestEntry> manifest, String userContext) {
        run.setManifest(manifest);
        run.setUserContext(userContext);
    }

    public void call(LlmReply.Usage u) {
        usage = usage.plus(u);
        run.setCalls(run.getCalls() + 1);
    }

    public void tool(String name, boolean ok, long ms, String summary) {
        run.getToolCalls().add(new AssistantSession.ToolCall(name, ok, ms, summary));
    }

    public AssistantSession.StageRun finish(String outcome, String error) {
        run.setMs(System.currentTimeMillis() - start);
        run.setInputTokens(usage.input());
        run.setOutputTokens(usage.output());
        run.setCacheReadTokens(usage.cacheRead());
        run.setCacheWriteTokens(usage.cacheWrite());
        double cost = props.getPrice().cost(usage.input(), usage.output(), usage.cacheRead(), usage.cacheWrite());
        run.setCostUsd(Math.round(cost * 10000) / 10000.0);
        run.setOutcome(outcome);
        run.setError(error);
        return run;
    }
}
