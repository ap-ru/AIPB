package com.aipb.seed;

import com.aipb.model.Layout;

import java.time.Instant;
import java.util.*;

/** The default dynamic layout. */
public final class Defaults {
    private Defaults() {}

    public static Layout.Widget widget(String type, String title, int span, Object... kv) {
        Layout.Widget w = new Layout.Widget();
        w.setId(type + "-" + UUID.randomUUID().toString().substring(0, 6));
        w.setType(type);
        w.setTitle(title);
        w.setSpan(span);
        w.setCreatedBy("system");
        Map<String, Object> cfg = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) cfg.put((String) kv[i], kv[i + 1]);
        w.setConfig(cfg);
        return w;
    }

    public static Layout.Page page(String id, String title, String icon, String desc, boolean system, Layout.Widget... widgets) {
        Layout.Page p = new Layout.Page();
        p.setId(id);
        p.setTitle(title);
        p.setIcon(icon);
        p.setDescription(desc);
        p.setSystem(system);
        p.setCreatedBy("system");
        p.setWidgets(new ArrayList<>(List.of(widgets)));
        return p;
    }

    /** A placed library widget: renders the widget definition {@code definitionId}. */
    public static Layout.Widget spec(String definitionId, String title, int span) {
        Layout.Widget w = widget("spec", title, span, "definitionId", definitionId);
        w.setId(definitionId + "-" + UUID.randomUUID().toString().substring(0, 6));
        return w;
    }

    public static Layout.PageParam param(String name, String label, String collection, String valueField, String labelField) {
        Layout.PageParam p = new Layout.PageParam();
        p.setName(name);
        p.setLabel(label);
        p.setCollection(collection);
        p.setValueField(valueField);
        p.setLabelField(labelField);
        return p;
    }

    /** Command Center's lens: a period (last 12 months by default) and filters by client attributes and line of business. */
    public static Layout.PageContext commandCenterContext() {
        Layout.PageContext c = new Layout.PageContext();
        Layout.PeriodSlot p = new Layout.PeriodSlot();
        p.setDefaultOption("t12m");
        p.getOptions().addAll(List.of(period("t12m", "Last 12 months", "trailing:month:12"), period("ytd", "Year to date", "ytd"),
                period("l4q", "Last 4 quarters", "lastClosed:quarter:4"), period("t24m", "Last 24 months", "trailing:month:24")));
        c.setPeriod(p);
        c.getFilters().addAll(List.of(filter("region", "Region"), filter("clientSegment", "Segment"), filter("tier", "Tier"),
                filter("relationshipManager", "RM"), filter("lob", "Line of business")));
        return c;
    }

    /** Command Center's widgets: book-wide figures from the starter definitions that follow the page (scope inherit). */
    public static List<Layout.Widget> commandCenterWidgets() {
        return new ArrayList<>(List.of(
                spec("client-exposure", "Credit exposure", 4),
                spec("client-deposits", "Deposits", 4),
                spec("client-open-pipeline", "Open pipeline", 4),
                spec("book-revenue-by-lob-monthly", "Monthly revenue by line of business", 8),
                spec("client-revenue-by-lob", "Revenue by line of business", 4)));
    }

    public static Layout.PeriodOption period(String id, String label, String windowKey) {
        Layout.PeriodOption o = new Layout.PeriodOption();
        o.setId(id);
        o.setLabel(label);
        o.setWindow(windowKey);
        return o;
    }

    public static Layout.FilterSlot filter(String dimension, String label) {
        Layout.FilterSlot f = new Layout.FilterSlot();
        f.setDimension(dimension);
        f.setLabel(label);
        return f;
    }

    public static Layout defaultLayout() {
        Layout l = new Layout();
        l.setId("default");
        l.setOwner("system");
        l.setVersion(1);
        l.setUpdatedAt(Instant.now());
        // The whole book, through the period and filters in the top bar; the same definitions as Client 360's.
        Layout.Page cc = page("command-center", "Command Center", "grid",
                "Single pane of glass across the commercial bank", true, commandCenterWidgets().toArray(Layout.Widget[]::new));
        cc.setContext(commandCenterContext());
        l.getPages().add(cc);

        // Built entirely from widget specs over the source data (bank), scoped by the client picker.
        Layout.Page client = page("client", "Client 360", "users",
                "One client's relationship, built from widget specs over the source data", true,
                spec("client-revenue-ytd", "Revenue YTD", 3),
                spec("client-exposure", "Credit exposure", 3),
                spec("client-deposits", "Deposits", 3),
                spec("client-open-pipeline", "Open pipeline", 3),
                spec("client-revenue-trend", "Revenue trend", 7),
                spec("client-revenue-by-lob", "Revenue by line of business", 5),
                spec("client-holdings", "Product holdings", 12),
                spec("client-covenants", "Covenants", 7),
                spec("client-active-signals", "Active signals", 5),
                spec("client-open-opportunities", "Open opportunities", 8),
                spec("client-contacts", "Key contacts", 4));
        client.getParams().add(param("clientId", "Client", "Client", "clientId", "name"));
        l.getPages().add(client);
        return l;
    }
}
