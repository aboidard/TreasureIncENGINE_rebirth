package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** All the items of a user; userFound=false is a cached "unknown public key" marker. */
public record ItemList(
        @JsonProperty("userFound") boolean userFound,
        @JsonProperty("items") List<ItemInfo> items) {

    public static ItemList unknownUser() {
        return new ItemList(false, List.of());
    }
}
