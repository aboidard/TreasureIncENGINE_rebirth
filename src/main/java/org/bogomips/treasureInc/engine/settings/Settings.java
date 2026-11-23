package org.bogomips.treasureInc.engine.settings;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The settings of the game, changed from Maestro: the maintenance and the oldest version of the Unity client allowed
 * to play. Kept in the database (table app_settings), mirrored in Redis for the API (see {@link SettingsService}).
 *
 * @param maintenance      the maintenance planned or under way
 * @param minClientVersion the oldest version of the Unity client that may log in, e.g. "0.22" ("0": any version)
 * @param updatedAt        the last change; null before the first one
 */
public record Settings(Maintenance maintenance, String minClientVersion, Instant updatedAt) {

    /** Numbers separated by dots, as Application.version of Unity: "1", "0.22", "1.4.2". */
    public static final Pattern VERSION = Pattern.compile("\\d{1,6}(\\.\\d{1,6}){0,3}");
    public static final int MAX_REASON = 500;
    public static final Settings DEFAULT = new Settings(Maintenance.OFF, "0", null);

    /**
     * The maintenance switch. While {@code enabled}, the requests of the game are refused from {@code start} (now
     * when null) until {@code end} (until it is switched off when null): {@link #active}.
     *
     * @param reason what the players read
     */
    public record Maintenance(boolean enabled, String reason, Instant start, Instant end) {

        public static final Maintenance OFF = new Maintenance(false, null, null, null);

        public boolean active(Instant now) {
            return enabled && (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
        }

        /** Seconds until the planned end, at least 1; null when no end is planned or it is past. */
        public Long secondsLeft(Instant now) {
            if (end == null || !now.isBefore(end)) {
                return null;
            }
            return Math.max(1, end.getEpochSecond() - now.getEpochSecond());
        }

        public Map<String, Object> toMap(Instant now) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled);
            m.put("active", active(now));
            m.put("reason", reason);
            m.put("start", iso(start));
            m.put("end", iso(end));
            return m;
        }

        /**
         * Reads {"enabled", "reason", "start", "end"} (ISO-8601 times, null or absent when not planned).
         *
         * @throws IllegalArgumentException with what is wrong
         */
        public static Maintenance parse(JsonNode node) {
            if (node == null || !node.isObject() || !node.path("enabled").isBoolean()) {
                throw new IllegalArgumentException("enabled (true or false) is required");
            }
            boolean enabled = node.get("enabled").asBoolean();
            JsonNode reasonNode = node.get("reason");
            if (reasonNode != null && !reasonNode.isNull() && !reasonNode.isTextual()) {
                throw new IllegalArgumentException("reason is a text");
            }
            String reason = reasonNode == null || reasonNode.isNull() || reasonNode.asText().isBlank()
                    ? null : reasonNode.asText().strip();
            if (reason != null && reason.length() > MAX_REASON) {
                throw new IllegalArgumentException("reason is " + MAX_REASON + " characters at most");
            }
            if (enabled && reason == null) {
                throw new IllegalArgumentException("reason is required to switch the maintenance on");
            }
            Instant start = instant(node, "start");
            Instant end = instant(node, "end");
            if (start != null && end != null && !end.isAfter(start)) {
                throw new IllegalArgumentException("end must be after start");
            }
            return new Maintenance(enabled, reason, start, end);
        }

        private static Instant instant(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                return null;
            }
            if (!value.isTextual()) {
                throw new IllegalArgumentException(field + " is an ISO-8601 time, e.g. 2026-11-22T14:00:00Z");
            }
            try {
                return Instant.parse(value.asText());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(field + " is an ISO-8601 time, e.g. 2026-11-22T14:00:00Z");
            }
        }
    }

    public Settings withMaintenance(Maintenance m, Instant at) {
        return new Settings(m, minClientVersion, at);
    }

    public Settings withMinClientVersion(String version, Instant at) {
        return new Settings(maintenance, version, at);
    }

    /** The payload of the settings requests and the JSON mirrored in Redis. */
    public Map<String, Object> toMap(Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maintenance", maintenance.toMap(now));
        m.put("minClientVersion", minClientVersion);
        m.put("updatedAt", iso(updatedAt));
        return m;
    }

    /** Reads {@link #toMap}; the default settings for what is missing. */
    public static Settings fromJson(JsonNode node) {
        JsonNode m = node.path("maintenance");
        Maintenance maintenance = m.isObject()
                ? new Maintenance(m.path("enabled").asBoolean(false), textOrNull(m, "reason"),
                        instantOrNull(m, "start"), instantOrNull(m, "end"))
                : Maintenance.OFF;
        String version = textOrNull(node, "minClientVersion");
        return new Settings(maintenance, version == null ? DEFAULT.minClientVersion() : version,
                instantOrNull(node, "updatedAt"));
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
