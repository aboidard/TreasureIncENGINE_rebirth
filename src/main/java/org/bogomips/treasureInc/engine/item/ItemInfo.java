package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

public record ItemInfo(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("graphics") Integer graphics,
        @JsonProperty("price") Integer price,
        @JsonProperty("rarity") String rarity,
        @JsonProperty("createdat") String createdAt,
        @JsonProperty("updatedat") String updatedAt,
        @JsonProperty("descriptionId") Integer descriptionId) {

    /** Item whose description is plain text (database items). */
    public ItemInfo(int id, String name, String description, Integer graphics, Integer price, String rarity,
                    String createdAt, String updatedAt) {
        this(id, name, description, graphics, price, rarity, createdAt, updatedAt, null);
    }

    /** Plain text if present, otherwise the catalogue text for the stored description id. */
    public String resolvedDescription() {
        return description != null ? description : ItemTexts.description(Rarity.fromValue(rarity), descriptionId);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", resolvedDescription());
        m.put("graphics", graphics);
        m.put("price", price);
        m.put("rarity", rarity);
        m.put("createdat", createdAt);
        m.put("updatedat", updatedAt);
        return m;
    }
}
