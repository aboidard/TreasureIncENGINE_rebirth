package org.bogomips.treasureInc.engine.expedition;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.user.UserService.SpendResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** Expedition request types: validates params, calls the services and shapes the reply. */
@ApplicationScoped
class ExpeditionEndpoints {

    private final ExpeditionService expeditions;
    private final ExpeditionRules rules;

    ExpeditionEndpoints(ExpeditionService expeditions, ExpeditionRules rules) {
        this.expeditions = expeditions;
        this.rules = rules;
    }

    /** Reveals the rarity of a case, for the cost given by the client. */
    @Produces
    RequestHandler digCase() {
        return RequestHandler.of("digCase", this::dig);
    }

    /** Pays for an expedition, whose cost depends on its location and difficulty: 402 if the balance is too low. */
    @Produces
    RequestHandler startExpedition() {
        return RequestHandler.of("startExpedition", this::start);
    }

    /** Opens the chest of an expedition: the number of items was set when it was sent. */
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
        if (publicKey == null || privateKey == null || location == null || difficulty == null
                || !rules.isValidDifficulty(difficulty) || (hasCase && (caseId == null || !rules.isValidCase(caseId)))) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return expeditions.start(publicKey, privateKey, location, difficulty, caseId).map(result -> {
            if (result.status() == SpendResult.Status.UNAUTHORIZED) {
                return Protocol.unauthorized();
            }
            if (result.status() == SpendResult.Status.NOT_ENOUGH_MONEY) {
                return notEnoughMoney(result.money());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("expeditionId", result.expedition().id());
            payload.put("money", result.money());
            payload.put("itemCount", result.expedition().itemCount());
            return Protocol.reply(200, "OK", payload);
        });
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
