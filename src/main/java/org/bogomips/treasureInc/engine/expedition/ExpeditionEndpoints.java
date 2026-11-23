package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.character.CharacterService;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.user.UserService.SpendResult;

import org.bogomips.treasureInc.engine.events.EventService;
import org.bogomips.treasureInc.engine.events.GameEvent;
import org.bogomips.treasureInc.engine.user.UserService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Expedition request types: validates params, calls the services and shapes the reply. */
@ApplicationScoped
class ExpeditionEndpoints {

    private final ExpeditionService expeditions;
    private final ExpeditionRules rules;
    private final CharacterService characters;
    private final EventService events;
    private final UserService users;

    ExpeditionEndpoints(ExpeditionService expeditions, ExpeditionRules rules, CharacterService characters,
                        EventService events, UserService users) {
        this.expeditions = expeditions;
        this.rules = rules;
        this.characters = characters;
        this.events = events;
        this.users = users;
    }

    /**
     * The events open now, for a player: {"events": [{code, name, description, image, level, floors,
     * rewards: {gems, money, items}, maxPerDay, runsToday, remainingToday, end}]}.
     */
    @Produces
    RequestHandler getEvents() {
        return RequestHandler.of("getEvents", this::events);
    }

    /**
     * params: {user, private_key, event, character}: sends the character to an open event, for free; the reply is
     * that of startExpedition. 404 when the event is not open, 429 when the player already went there as many times
     * today as the event allows.
     */
    @Produces
    RequestHandler startEventExpedition() {
        return RequestHandler.of("startEventExpedition", this::startEvent);
    }

    /** Reveals the rarity of a case, for the cost given by the client. */
    @Produces
    RequestHandler digCase() {
        return RequestHandler.of("digCase", this::dig);
    }

    /**
     * Pays for an expedition, whose cost depends on its location and difficulty: 402 if the balance is too low.
     * The reply tells what happens to the character on the way ("run"), and "xp" what it will earn in the end.
     */
    @Produces
    RequestHandler startExpedition() {
        return RequestHandler.of("startExpedition", this::start);
    }

    /**
     * Opens the chest of an expedition: the number of items was set when it was sent. The reply also says what
     * the expedition brought to its character ("xp"), null when nobody led it, and how many of its floors the
     * character finished ("floorsCompleted" out of "floors").
     */
    @Produces
    RequestHandler generateItemsForUser() {
        return RequestHandler.of("generateItemsForUser", this::open);
    }

    private Uni<Object> dig(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        String location = rules.locationKey(Protocol.text(params, "location"));
        Integer caseId = required(params, "case_id");
        // no default: a missing cost is a bad request, not a free dig
        Integer cost = required(params, "cost");
        if (publicKey == null || privateKey == null || location == null || caseId == null || cost == null
                || !rules.isValidCase(caseId) || cost < 0) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return expeditions.dig(publicKey, privateKey, location, caseId, cost).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_ENOUGH_MONEY -> notEnoughMoney(result.money());
            case OK -> {
                if (result.noCharacter()) {
                    yield Protocol.reply(409, "No character available");
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("rarity", result.rarity());
                payload.put("money", result.money());
                yield Protocol.reply(200, "OK", payload);
            }
        });
    }

    private Uni<Object> start(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        String location = rules.locationKey(Protocol.text(params, "location"));
        Integer difficulty = required(params, "difficulty");
        boolean hasCase = params != null && params.hasNonNull("case_id");
        Integer caseId = hasCase ? required(params, "case_id") : null;
        Integer character = required(params, "character");
        if (publicKey == null || privateKey == null || location == null || difficulty == null || character == null
                || !rules.isValidDifficulty(difficulty) || !characters.isValid(character)
                || (hasCase && (caseId == null || !rules.isValidCase(caseId)))) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return expeditions.start(publicKey, privateKey, location, difficulty, caseId, character)
                .map(ExpeditionEndpoints::startReply);
    }

    private Uni<Object> events(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        if (publicKey == null || privateKey == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        List<GameEvent> open = events.open();
        return users.openSession(publicKey, privateKey).flatMap(user -> user == null
                ? Uni.createFrom().item(Protocol.unauthorized())
                : events.runsToday(publicKey, open).map(runs -> Protocol.reply(200, "OK", Map.of("events",
                        open.stream().map(e -> e.toPlayerMap(runs.getOrDefault(e.code(), 0))).toList()))));
    }

    private Uni<Object> startEvent(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        String code = Protocol.text(params, "event");
        Integer character = required(params, "character");
        if (publicKey == null || privateKey == null || code == null || character == null
                || !characters.isValid(character)) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        GameEvent event = events.openEvent(code);
        if (event == null) {
            return Uni.createFrom().item(Protocol.reply(404, "Event not found or closed"));
        }
        return expeditions.startEvent(publicKey, privateKey, event, character).map(ExpeditionEndpoints::startReply);
    }

    private static Object startReply(ExpeditionService.StartResult result) {
        {
            if (result.status() == SpendResult.Status.UNAUTHORIZED) {
                return Protocol.unauthorized();
            }
            if (result.status() == SpendResult.Status.NOT_ENOUGH_MONEY) {
                return notEnoughMoney(result.money());
            }
            if (result.characterBusy()) {
                return Protocol.reply(409, "The character is on an expedition");
            }
            if (result.unknownCharacter()) {
                return Protocol.reply(404, "Character not found");
            }
            if (result.limitReached()) {
                return Protocol.reply(429, "Already done today", Map.of("money", result.money()));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("expeditionId", result.expedition().id());
            payload.put("character", result.expedition().character());
            payload.put("money", result.money());
            payload.put("itemCount", result.expedition().itemCount());
            payload.put("xp", result.expedition().xp());
            payload.put("run", result.run().toMap());
            payload.put("floors", result.expedition().difficulty());
            return Protocol.reply(200, "OK", payload);
        }
    }

    private Uni<Object> open(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        String expeditionId = Protocol.text(params, "expedition_id");
        if (publicKey == null || privateKey == null || expeditionId == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return expeditions.open(publicKey, privateKey, expeditionId).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_FOUND -> Protocol.reply(404, "Expedition not found");
            case OK -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("items", result.items().stream().map(ItemInfo::toMap).toList());
                payload.put("xp", result.xp() == null ? null : result.xp().toMap());
                payload.put("floors", result.expedition().difficulty());
                payload.put("floorsCompleted", result.expedition().completed());
                // what an event's chest gives besides the items, and the balances afterwards
                payload.put("gems", result.gems());
                payload.put("money", result.money());
                payload.put("balance", result.balance() == null ? null
                        : Map.of("money", result.balance().money(), "gems", result.balance().gems()));
                yield Protocol.reply(200, "OK", payload);
            }
        });
    }

    /** Integer field that must be present; null when absent or not an integer. */
    private static Integer required(JsonNode params, String field) {
        return params == null || !params.hasNonNull(field) ? null : Protocol.integer(params, field, 0);
    }

    private static Object notEnoughMoney(int money) {
        return Protocol.reply(402, "Not enough money", Map.of("money", money));
    }
}
