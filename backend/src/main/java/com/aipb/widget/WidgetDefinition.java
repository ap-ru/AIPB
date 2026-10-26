package com.aipb.widget;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A widget definition in the library (aipb.widgets): the live, approved version of a widget. The spec is stored
 * verbatim as JSON text - its aggregation pipeline is full of $-prefixed keys that do not belong in a stored
 * document's field names - and the fields worth listing or filtering on are copied alongside it. Pages place a
 * definition by id (see Layout.Widget config), so they always show its current version. Every version is kept in
 * aipb.widget_versions; changes wait in aipb.widget_submissions until approved.
 */
@Data
@NoArgsConstructor
@Document("widgets")
public class WidgetDefinition {
    public static final String STOCK = "stock";  // a starter widget from the repo
    public static final String BUILT = "built";  // made in the app

    @Id
    private String id;
    private int version;         // the current approved version
    private String origin;       // stock | built
    private String title;
    private String description;
    private String primitive;
    private String collection;
    private List<String> params;
    private String author;       // "starter" | "workbench" | later "assistant"
    private String spec;         // the WidgetSpec JSON, as saved
    private String owner;        // actor who built it (e.g. demo-builder); "system" for stock widgets
    private Instant createdAt;
    private Instant updatedAt;
}
