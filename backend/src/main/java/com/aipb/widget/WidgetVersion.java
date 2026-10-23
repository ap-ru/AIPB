package com.aipb.widget;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One approved version of a widget (aipb.widget_versions), never changed once written. The widget's live copy in
 * aipb.widgets is always one of these; restoring an old version writes it again as a new version.
 */
@Data
@NoArgsConstructor
@Document("widget_versions")
public class WidgetVersion {
    public static final String STOCK = "stock";        // loaded from a starter file in the repo
    public static final String APPROVED = "approved";  // an approved submission
    public static final String RESTORE = "restore";    // a copy of an older version
    public static final String MIGRATED = "migrated";  // in the library before versions were kept

    @Id
    private String id;            // widgetId@version
    private String widgetId;
    private int version;
    private String title;
    private String description;
    private String primitive;
    private String spec;          // the WidgetSpec JSON, as saved
    private String source;        // stock | approved | restore | migrated
    private String submissionId;  // when source is approved
    private Integer restoredFrom; // when source is restore
    private String approvedByRole;
    private Instant createdAt;

    public static String key(String widgetId, int version) { return widgetId + "@" + version; }
}
