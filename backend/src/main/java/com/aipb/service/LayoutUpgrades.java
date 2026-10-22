package com.aipb.service;

import com.aipb.model.Layout;
import com.aipb.repo.LayoutRepository;
import com.aipb.seed.Defaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Brings saved layouts up to date without a reset, so bankers keep the pages and widgets they added. Each upgrade is
 * idempotent and only adds what is missing:
 * <ul>
 *   <li>page context (2026-10): Command Center gets its period and filters, and the book-wide widgets that follow them
 *       (those not already on the page), in the default order.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class LayoutUpgrades implements ApplicationRunner {

    private final LayoutRepository repo;
    private final LayoutService layouts;

    @Override
    public void run(ApplicationArguments args) {
        for (Layout l : repo.findAll()) {
            if (addPageContext(l)) {
                layouts.save(l);
                log.info("Layout {}: Command Center now has a period and filters", l.getId());
            }
        }
    }

    static boolean addPageContext(Layout l) {
        Layout.Page cc = l.page("command-center");
        if (cc == null || cc.getContext() != null) return false;
        cc.setContext(Defaults.commandCenterContext());
        Set<String> present = new HashSet<>();
        cc.getWidgets().forEach(w -> { Object d = w.getConfig() == null ? null : w.getConfig().get("definitionId"); if (d != null) present.add(d.toString()); });
        List<Layout.Widget> wanted = Defaults.commandCenterWidgets();
        int at = 0;
        for (Layout.Widget w : wanted) {
            String def = String.valueOf(w.getConfig().get("definitionId"));
            int existing = indexOf(cc, def);
            if (existing >= 0) { at = existing + 1; continue; }
            cc.getWidgets().add(Math.min(at, cc.getWidgets().size()), w);
            at++;
        }
        return true;
    }

    private static int indexOf(Layout.Page p, String definitionId) {
        for (int i = 0; i < p.getWidgets().size(); i++) {
            Object d = p.getWidgets().get(i).getConfig() == null ? null : p.getWidgets().get(i).getConfig().get("definitionId");
            if (definitionId.equals(d)) return i;
        }
        return -1;
    }
}
