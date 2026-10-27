package com.aipb.skills;

import com.aipb.identity.Actor;
import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The Skills Studio catalog. Templates are what an assistant run is started from, so only roles that build skills
 * may list them; with a page, the list holds only the templates that fit it.
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillTemplateRegistry templates;
    private final LayoutService layouts;

    @GetMapping("/templates")
    public List<Map<String, Object>> list(@RequestParam(required = false) String layoutId, @RequestParam(required = false) String pageId,
                                          Actor actor) {
        actor.requireAuthor();
        List<SkillTemplate> out = templates.all();
        if (pageId != null) {
            Layout.Page page = layouts.get(layoutId == null ? "default" : layoutId).page(pageId);
            if (page == null) throw new NoSuchElementException("page " + pageId);
            out = templates.forPage(page);
        }
        return out.stream().map(SkillController::summary).toList();
    }

    @GetMapping("/templates/{id}")
    public Map<String, Object> get(@PathVariable String id, Actor actor) {
        actor.requireAuthor();
        return summary(templates.get(id));
    }

    /** What the Studio shows and asks; guidance and constraints stay server-side, where they steer the run. */
    static Map<String, Object> summary(SkillTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("version", t.version());
        m.put("kind", t.kind());
        m.put("title", t.title());
        m.put("summary", t.summary());
        m.put("icon", t.icon());
        m.put("category", t.category());
        m.put("pageScopes", t.pageScopes());
        m.put("inputs", t.inputs());
        m.put("suggestions", t.suggestions());
        m.put("primitives", t.constraints().primitives());
        return m;
    }
}
