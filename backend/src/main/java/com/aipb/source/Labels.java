package com.aipb.source;

import java.util.Map;

/**
 * The bank graph's label for each MongoDB collection the data used to live in, for what was stored before the move
 * (page parameters naming {@code clients}, custom specs naming their collection). The dictionary entry of each label
 * records the same mapping ({@code collection}).
 */
public final class Labels {

    static final Map<String, String> BY_COLLECTION = Map.ofEntries(
            Map.entry("products", "Product"), Map.entry("clients", "Client"), Map.entry("holdings", "Holding"),
            Map.entry("revenue_monthly", "RevenueMonth"), Map.entry("covenants", "Covenant"), Map.entry("opportunities", "Opportunity"),
            Map.entry("signals", "Signal"), Map.entry("contacts", "Contact"), Map.entry("activities", "Activity"));

    private Labels() {}

    /** The label for a name that may be a former collection name; any other name is returned as it is. */
    public static String of(String name) {
        return name == null ? null : BY_COLLECTION.getOrDefault(name, name);
    }
}
