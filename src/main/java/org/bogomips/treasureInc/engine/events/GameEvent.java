package org.bogomips.treasureInc.engine.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An event: an expedition limited in time or special, besides the locations of the story. Kept in the database
 * (table game_events), changed from Maestro, mirrored in Redis (see {@link EventService}).
 *
 * @param code        the id of the event, e.g. "lancement"
 * @param image       the name of a sprite of the client (Resources/Events/&lt;image&gt;) or the URL of a picture
 * @param level       the difficulty, from 1 (easy, one star) to 3 (hard, three stars): what is met there
 * @param floors      the number of floors of its expeditions
 * @param rewardGems  gems given when the chest is opened (the share of the floors finished when the character died)
 * @param rewardMoney money given likewise
 * @param rewardItems items in the chest, likewise
 * @param maxPerDay   how many expeditions a player may send there each day; 0: no limit
 * @param start       open from then; null: at once
 * @param end         open until then; null: for ever
 * @param enabled     closed whatever the dates when false
 * @param sortOrder   the events are listed by increasing sortOrder
 * @param updatedAt   the last change; null when never changed
 */
public record GameEvent(String code, String name, String description, String image, int level, int floors,
                        int rewardGems, int rewardMoney, int rewardItems, int maxPerDay, Instant start, Instant end,
                        boolean enabled, int sortOrder, Instant updatedAt) {

    public static final Pattern CODE = Pattern.compile("[a-z0-9_-]{1,40}");
    public static final int MAX_LEVEL = 3;
    public static final int MAX_FLOORS = 10;
    public static final int MAX_ITEMS = 15;
    public static final int MAX_NAME = 100;
    public static final int MAX_DESCRIPTION = 500;
    public static final int MAX_IMAGE = 500;
    /** The location of the expeditions sent to an event: no location of the story has a colon in its name. */
    public static final String LOCATION_PREFIX = "event:";

    /** Whether players may send an expedition there now. */
    public boolean open(Instant now) {
        return enabled && (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
    }

    /** Ended for good: closed now and for ever (Maestro still lists it). */
    public boolean over(Instant now) {
        return end != null && !now.isBefore(end);
    }

    public String location() {
        return LOCATION_PREFIX + code;
    }

    /** The code of the event an expedition was sent to, from its location; null for a location of the story. */
    public static String codeOf(String location) {
        return location != null && location.startsWith(LOCATION_PREFIX)
                ? location.substring(LOCATION_PREFIX.length()) : null;
    }

    public GameEvent withUpdatedAt(Instant at) {
        return new GameEvent(code, name, description, image, level, floors, rewardGems, rewardMoney, rewardItems,
                maxPerDay, start, end, enabled, sortOrder, at);
    }

    /** Everything about the event: the JSON mirrored in Redis and what Maestro reads. */
    public Map<String, Object> toMap(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("description", description);
        m.put("image", image);
        m.put("level", level);
        m.put("floors", floors);
        m.put("rewards", rewards());
        m.put("maxPerDay", maxPerDay);
        m.put("start", iso(start));
        m.put("end", iso(end));
        m.put("enabled", enabled);
        m.put("open", open(now));
        m.put("sortOrder", sortOrder);
        m.put("updatedAt", iso(updatedAt));
        return m;
    }

    /** What a player sees: runsToday is how many expeditions this player sent there today. */
    public Map<String, Object> toPlayerMap(int runsToday) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("description", description);
        m.put("image", image);
        m.put("level", level);
        m.put("floors", floors);
        m.put("rewards", rewards());
        m.put("maxPerDay", maxPerDay);
        m.put("runsToday", runsToday);
        // null when there is no limit
        m.put("remainingToday", maxPerDay == 0 ? null : Math.max(0, maxPerDay - runsToday));
        m.put("end", iso(end));
        return m;
    }

    private Map<String, Object> rewards() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("gems", rewardGems);
        r.put("money", rewardMoney);
        r.put("items", rewardItems);
        return r;
    }

    /** Reads {@link #toMap}; null when it is not an event. */
    public static GameEvent fromJson(JsonNode node) {
        String code = textOrNull(node, "code");
        if (code == null) {
            return null;
        }
        JsonNode rewards = node.path("rewards");
        return new GameEvent(code, textOrNull(node, "name"), textOrNull(node, "description"),
                textOrNull(node, "image"), node.path("level").asInt(1), node.path("floors").asInt(MAX_FLOORS),
                rewards.path("gems").asInt(0), rewards.path("money").asInt(0), rewards.path("items").asInt(0),
                node.path("maxPerDay").asInt(0), instantOrNull(node, "start"), instantOrNull(node, "end"),
                node.path("enabled").asBoolean(false), node.path("sortOrder").asInt(0),
                instantOrNull(node, "updatedAt"));
    }

    /**
     * Reads an event sent by Maestro: {code, name, description, image, level, floors, rewards: {gems, money, items},
     * maxPerDay, start, end, enabled, sortOrder}. code, name, level and floors are required.
     *
     * @throws IllegalArgumentException with what is wrong
     */
    public static GameEvent parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("an event is required");
        }
        String code = text(node, "code", 40, true);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code is 1 to 40 lowercase letters, digits, - or _");
        }
        String name = text(node, "name", MAX_NAME, true);
        String description = text(node, "description", MAX_DESCRIPTION, false);
        String image = text(node, "image", MAX_IMAGE, false);
        int level = integer(node, "level", 1, MAX_LEVEL, null);
        int floors = integer(node, "floors", 1, MAX_FLOORS, null);
        JsonNode rewards = node.path("rewards");
        if (!rewards.isMissingNode() && !rewards.isNull() && !rewards.isObject()) {
            throw new IllegalArgumentException("rewards is {gems, money, items}");
        }
        int gems = integer(rewards, "gems", 0, Integer.MAX_VALUE, 0);
        int money = integer(rewards, "money", 0, Integer.MAX_VALUE, 0);
        int items = integer(rewards, "items", 0, MAX_ITEMS, 0);
        int maxPerDay = integer(node, "maxPerDay", 0, 1000, 1);
        Instant start = instant(node, "start");
        Instant end = instant(node, "end");
        if (start != null && end != null && !end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        JsonNode enabled = node.get("enabled");
        if (enabled != null && !enabled.isNull() && !enabled.isBoolean()) {
            throw new IllegalArgumentException("enabled is true or false");
        }
        int sortOrder = integer(node, "sortOrder", Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        return new GameEvent(code, name, description, image, level, floors, gems, money, items, maxPerDay, start, end,
                enabled == null || enabled.isNull() || enabled.asBoolean(), sortOrder, null);
    }

    private static String text(JsonNode node, String field, int max, boolean required) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return null;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(field + " is a text");
        }
        String text = v.asText().strip();
        if (text.length() > max) {
            throw new IllegalArgumentException(field + " is " + max + " characters at most");
        }
        return text;
    }

    private static int integer(JsonNode node, String field, int min, int max, Integer defaultValue) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            if (defaultValue == null) {
                throw new IllegalArgumentException(field + " is required");
            }
            return defaultValue;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < min || v.asInt() > max) {
            throw new IllegalArgumentException(field + " is a whole number"
                    + (min == Integer.MIN_VALUE ? "" : " from " + min)
                    + (max == Integer.MAX_VALUE ? "" : " to " + max));
        }
        return v.asInt();
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            return null;
        }
        try {
            if (value.isTextual()) {
                return Instant.parse(value.asText());
            }
        } catch (DateTimeParseException e) {
            // below
        }
        throw new IllegalArgumentException(field + " is an ISO-8601 time, e.g. 2026-11-22T14:00:00Z");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isTextual() ? null : v.asText();
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        String text = textOrNull(node, field);
        try {
            return text == null ? null : Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String iso(Instant t) {
        return t == null ? null : t.toString();
    }
}
