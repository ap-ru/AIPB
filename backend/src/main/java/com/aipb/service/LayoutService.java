package com.aipb.service;

import com.aipb.model.Layout;
import com.aipb.repo.LayoutRepository;
import com.aipb.seed.Defaults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** CRUD over the dynamic UI definition. Every mutation bumps the layout version. */
@Service
@RequiredArgsConstructor
public class LayoutService {

    private final LayoutRepository repo;

    public Layout get(String id) {
        return repo.findById(id).orElseGet(() -> {
            Layout l = repo.findById("default").orElseGet(Defaults::defaultLayout);
            l.setId(id);
            l.setOwner(id);
            return repo.save(l);
        });
    }

    /**
     * The layout as an actor sees it: without other people's personal placements (widgets not approved yet, placed by
     * their Builder and visible only to them).
     */
    public Layout get(String id, String actorId) {
        Layout l = get(id);
        l.getPages().forEach(p -> p.getWidgets().removeIf(w -> w.getVisibleTo() != null && !w.getVisibleTo().equals(actorId)));
        return l;
    }

    /** Puts back the placements an actor could not see (and so could not have sent) when they save a whole layout. */
    public Layout saveAs(Layout body, String actorId) {
        Layout stored = get(body.getId());
        for (Layout.Page sp : stored.getPages()) {
            Layout.Page bp = body.page(sp.getId());
            if (bp == null) continue;
            sp.getWidgets().stream().filter(w -> w.getVisibleTo() != null && !w.getVisibleTo().equals(actorId))
                    .filter(w -> bp.getWidgets().stream().noneMatch(x -> x.getId().equals(w.getId())))
                    .forEach(w -> bp.getWidgets().add(w));
        }
        return save(body);
    }

    /** A personal widget was approved: its placements become visible to everyone. */
    public void openPlacements(String definitionId) {
        forPlacements(definitionId, (p, w) -> { w.setVisibleTo(null); return false; });
    }

    /** A personal widget was rejected: it can never go live, so its placements come off every page. */
    public void removePlacements(String definitionId) {
        forPlacements(definitionId, (p, w) -> true);
    }

    /** Applies {@code change} to every placement of a definition; it returns true to remove the placement. */
    private void forPlacements(String definitionId, java.util.function.BiPredicate<Layout.Page, Layout.Widget> change) {
        for (Layout l : repo.findAll()) {
            boolean dirty = false;
            for (Layout.Page p : l.getPages()) {
                var it = p.getWidgets().iterator();
                while (it.hasNext()) {
                    Layout.Widget w = it.next();
                    Object def = w.getConfig() == null ? null : w.getConfig().get("definitionId");
                    if (!definitionId.equals(def)) continue;
                    if (change.test(p, w)) it.remove();
                    dirty = true;
                }
            }
            if (dirty) save(l);
        }
    }

    public Layout save(Layout l) {
        l.setVersion(l.getVersion() + 1);
        l.setUpdatedAt(Instant.now());
        return repo.save(l);
    }

    public Layout reset(String id) {
        Layout l = Defaults.defaultLayout();
        l.setId(id);
        l.setOwner(id);
        repo.findById(id).ifPresent(old -> l.setVersion(old.getVersion()));
        return save(l);
    }

    public static String slug(String s) {
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return out.isEmpty() ? "page" : out;
    }

    public Layout.Widget addWidget(String layoutId, String pageId, Layout.Widget w, Integer position) {
        Layout l = get(layoutId);
        Layout.Page p = Optional.ofNullable(l.page(pageId)).orElseGet(() -> l.getPages().get(0));
        if (w.getId() == null || w.getId().isBlank() || p.getWidgets().stream().anyMatch(x -> x.getId().equals(w.getId())))
            w.setId(w.getType() + "-" + UUID.randomUUID().toString().substring(0, 6));
        if (w.getSpan() <= 0 || w.getSpan() > 12) w.setSpan(12);
        if (w.getConfig() == null) w.setConfig(new LinkedHashMap<>());
        if (position == null || position < 0 || position > p.getWidgets().size()) p.getWidgets().add(w);
        else p.getWidgets().add(position, w);
        save(l);
        return w;
    }

    public boolean removeWidget(String layoutId, String pageId, String widgetId) {
        Layout l = get(layoutId);
        Layout.Page p = l.page(pageId);
        if (p == null) return false;
        boolean removed = p.getWidgets().removeIf(w -> w.getId().equals(widgetId));
        if (removed) save(l);
        return removed;
    }

    public Layout.Widget updateWidget(String layoutId, String pageId, String widgetId, Layout.Widget patch) {
        Layout l = get(layoutId);
        Layout.Page p = l.page(pageId);
        if (p == null) throw new NoSuchElementException("page " + pageId);
        Layout.Widget w = p.getWidgets().stream().filter(x -> x.getId().equals(widgetId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("widget " + widgetId));
        if (patch.getTitle() != null) w.setTitle(patch.getTitle());
        if (patch.getSpan() > 0) w.setSpan(Math.min(12, patch.getSpan()));
        if (patch.getConfig() != null) w.setConfig(patch.getConfig());
        save(l);
        return w;
    }

    public Layout moveWidget(String layoutId, String pageId, String widgetId, int delta) {
        Layout l = get(layoutId);
        Layout.Page p = l.page(pageId);
        if (p == null) return l;
        List<Layout.Widget> ws = p.getWidgets();
        for (int i = 0; i < ws.size(); i++) {
            if (ws.get(i).getId().equals(widgetId)) {
                int j = Math.max(0, Math.min(ws.size() - 1, i + delta));
                Collections.swap(ws, i, j);
                break;
            }
        }
        return save(l);
    }

    public Layout.Page addPage(String layoutId, Layout.Page page) {
        Layout l = get(layoutId);
        String base = page.getId() == null || page.getId().isBlank() ? slug(page.getTitle()) : page.getId();
        String id = base;
        int n = 2;
        while (l.page(id) != null) id = base + "-" + n++;
        page.setId(id);
        if (page.getIcon() == null) page.setIcon("spark");
        if (page.getWidgets() == null) page.setWidgets(new ArrayList<>());
        page.getWidgets().forEach(w -> {
            if (w.getId() == null) w.setId(w.getType() + "-" + UUID.randomUUID().toString().substring(0, 6));
            if (w.getSpan() <= 0) w.setSpan(12);
        });
        l.getPages().add(insertionIndex(l, id), page);
        save(l);
        return page;
    }

    /** A default page that is created again goes back to its default slot (after the default pages before it); others go last. */
    private static int insertionIndex(Layout l, String pageId) {
        List<String> order = Defaults.defaultLayout().getPages().stream().map(Layout.Page::getId).toList();
        int at = order.indexOf(pageId);
        if (at < 0) return l.getPages().size();
        int idx = 0;
        for (int i = 0; i < l.getPages().size(); i++) if (order.subList(0, at).contains(l.getPages().get(i).getId())) idx = i + 1;
        return idx;
    }

    public boolean deletePage(String layoutId, String pageId) {
        return deletePage(layoutId, pageId, false);
    }

    /** System pages are only removed when {@code includeSystem} is set; a layout reset brings them back. */
    public boolean deletePage(String layoutId, String pageId, boolean includeSystem) {
        Layout l = get(layoutId);
        boolean removed = l.getPages().removeIf(p -> p.getId().equals(pageId) && (includeSystem || !p.isSystem()));
        if (removed) save(l);
        return removed;
    }
}
