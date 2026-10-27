package com.aipb.skills;

import com.aipb.assistant.AssistantSession;
import com.aipb.assistant.AssistantTools;

/** One assistant run, resolved once: the session, the template guiding it, and where its tools run. */
public record SkillRun(AssistantSession session, SkillTemplate template, AssistantTools.Context ctx) {

    public static SkillRun of(AssistantSession s, SkillTemplate t) {
        return new SkillRun(s, t, new AssistantTools.Context(s.getLayoutId(), s.getPageId(), s.getParams()));
    }
}
