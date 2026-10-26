package com.aipb.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Who the request is from and what each role may do - drives the role dropdown and what the UI shows. */
@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    @GetMapping
    public Map<String, Object> identity(Actor actor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actor", Map.of("id", actor.id(), "role", actor.role().id()));
        m.put("roles", roles());
        return m;
    }

    static List<Map<String, Object>> roles() {
        return Arrays.stream(Role.values()).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("label", r.label());
            m.put("description", r.description());
            m.put("canUseAssistant", r.canUseAssistant());
            m.put("canAuthor", r.canAuthor());
            m.put("canReview", r.canReview());
            return m;
        }).toList();
    }
}
