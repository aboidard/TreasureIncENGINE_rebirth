package org.bogomips.treasureInc.engine.gift;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.gift.GiftService.Reward;
import org.bogomips.treasureInc.engine.gift.GiftService.Target;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gift request types: the gift box of a player (getGifts, claimGift) and the sending of gifts (sendGift).
 * sendGift is an administration request, sent by Maestro straight on the Redis request channel: the API only relays
 * the request types of the game, so a player cannot reach it.
 */
@ApplicationScoped
class GiftEndpoints {

    private static final Logger LOG = Logger.getLogger(GiftEndpoints.class);
    private static final Pattern PUBLIC_KEY = Pattern.compile("\\d{11}");
    static final int MAX_TITLE = 120;
    static final int MAX_DAYS = 3650;
    static final int MAX_PUBLIC_KEYS = 10_000;

    private final GiftService gifts;

    GiftEndpoints(GiftService gifts) {
        this.gifts = gifts;
    }

    /** The gifts waiting in the box of the user. */
    @Produces
    RequestHandler getGifts() {
        return RequestHandler.of("getGifts", this::list);
    }

    /** Takes a gift: 404 when it is not one of the user's, 409 when already taken, 410 when expired. */
    @Produces
    RequestHandler claimGift() {
        return RequestHandler.of("claimGift", this::claim);
    }

    /** Puts a gift in the box of the players matching the target (administration, see the class). */
    @Produces
    RequestHandler sendGift() {
        return RequestHandler.admin("sendGift", this::send);
    }

    private Uni<Object> list(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        if (publicKey == null || privateKey == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return gifts.list(publicKey, privateKey).map(pending -> pending == null
                ? Protocol.unauthorized()
                : Protocol.reply(200, "OK", GiftService.toMap(pending)));
    }

    private Uni<Object> claim(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        Integer giftId = Protocol.integer(params, "gift", -1);
        if (publicKey == null || privateKey == null || giftId == null || giftId < 0) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return gifts.claim(publicKey, privateKey, giftId).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_FOUND -> Protocol.reply(404, "Gift not found");
            case ALREADY_CLAIMED -> Protocol.reply(409, "The gift is already claimed");
            case EXPIRED -> Protocol.reply(410, "The gift has expired");
            case OK -> {
                Reward given = result.gift().reward();
                Map<String, Object> reward = new LinkedHashMap<>();
                reward.put("money", given.money());
                reward.put("gems", given.gems());
                reward.put("items", result.items().stream().map(ItemInfo::toMap).toList());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("gift", result.gift().id());
                payload.put("title", result.gift().title());
                payload.put("money", result.money());
                payload.put("gems", result.gems());
                payload.put("reward", reward);
                payload.put("gifts", GiftService.toMap(result.gifts()));
                yield Protocol.reply(200, "OK", payload);
            }
        });
    }

    /**
     * params: {"title", "reward": {"money", "gems", "items", "rarity"}, "players": {"activeDays", "inactiveDays",
     * "createdDays", "publicKeys": []}, "expiresInDays"}; "players" and "expiresInDays" are optional.
     * Answers {"batch", "sent"}, or 400 with what is wrong.
     */
    private Uni<Object> send(JsonNode params) {
        String title = params == null ? null : Protocol.text(params, "title");
        if (title == null || title.isBlank() || title.strip().length() > MAX_TITLE) {
            return invalid("title is required, " + MAX_TITLE + " characters at most");
        }
        Reward reward = reward(params.get("reward"));
        if (reward == null || !reward.isValid()) {
            return invalid("reward needs money, gems or items (0 or more, " + Reward.MAX_AMOUNT
                    + " money or gems and " + Reward.MAX_ITEMS
                    + " items at most); rarity is common, uncommon, rare, epic or legendary and needs items");
        }
        JsonNode players = params.get("players");
        Target target;
        if (players == null || players.isNull()) {
            target = Target.EVERYONE;
        } else {
            target = players.isObject() ? target(players) : null;
            if (target == null) {
                return invalid("players may give activeDays, inactiveDays and createdDays (1 to " + MAX_DAYS
                        + "), and publicKeys (1 to " + MAX_PUBLIC_KEYS + " public keys of 11 digits)");
            }
        }
        Integer expiresInDays = days(params, "expiresInDays");
        if (expiresInDays != null && expiresInDays < 0) {
            return invalid("expiresInDays is 1 to " + MAX_DAYS);
        }
        Duration expiresIn = expiresInDays == null ? null : Duration.ofDays(expiresInDays);
        String cleanTitle = title.strip();
        return gifts.send(target, cleanTitle, reward, expiresIn).map(report -> {
            LOG.infof("event=gift_sent batch=%s sent=%d title=\"%s\" reward=%s target=%s expiresInDays=%s",
                    report.batch(), report.sent(), cleanTitle, reward, describe(target), expiresInDays);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batch", report.batch());
            payload.put("sent", report.sent());
            return Protocol.reply(200, "OK", payload);
        });
    }

    private static Uni<Object> invalid(String message) {
        return Uni.createFrom().item(Protocol.reply(400, "Bad Request: " + message));
    }

    /** Null when a value is not a whole number, or the rarity not a text. */
    private static Reward reward(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Integer money = Protocol.integer(node, "money", 0);
        Integer gems = Protocol.integer(node, "gems", 0);
        Integer items = Protocol.integer(node, "items", 0);
        JsonNode rarity = node.get("rarity");
        if (money == null || gems == null || items == null
                || (rarity != null && !rarity.isNull() && !rarity.isTextual())) {
            return null;
        }
        String rarityValue = rarity == null || rarity.isNull() ? null : rarity.asText();
        return new Reward(money, gems, items, rarityValue);
    }

    /** Null when a criterion is invalid. */
    private static Target target(JsonNode players) {
        Integer active = days(players, "activeDays");
        Integer inactive = days(players, "inactiveDays");
        Integer created = days(players, "createdDays");
        if ((active != null && active < 0) || (inactive != null && inactive < 0) || (created != null && created < 0)) {
            return null;
        }
        List<String> keys = null;
        JsonNode node = players.get("publicKeys");
        if (node != null && !node.isNull()) {
            if (!node.isArray() || node.isEmpty() || node.size() > MAX_PUBLIC_KEYS) {
                return null;
            }
            keys = new ArrayList<>();
            for (JsonNode key : node) {
                if (!key.isTextual() || !PUBLIC_KEY.matcher(key.asText()).matches()) {
                    return null;
                }
                keys.add(key.asText());
            }
            keys = List.copyOf(new java.util.LinkedHashSet<>(keys));
        }
        return new Target(active, inactive, created, keys);
    }

    /** Null when absent; -1 when present but not a number of days from 1 to MAX_DAYS. */
    private static Integer days(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        Integer days = Protocol.integer(node, field, 0);
        return days == null || days < 1 || days > MAX_DAYS ? -1 : days;
    }

    /** For the logs: the criteria, without the whole list of public keys. */
    private static String describe(Target target) {
        return "{activeDays=" + target.activeDays() + ", inactiveDays=" + target.inactiveDays()
                + ", createdDays=" + target.createdDays()
                + ", publicKeys=" + (target.publicKeys() == null ? null : target.publicKeys().size()) + "}";
    }
}
