package org.bogomips.treasureInc.engine.quest;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/** Quest request types: validates params, calls {@link QuestService} and shapes the reply. */
@ApplicationScoped
class QuestEndpoints {

    private final QuestService quests;

    QuestEndpoints(QuestService quests) {
        this.quests = quests;
    }

    /** The daily and weekly quests of the user, with how far each one is. */
    @Produces
    RequestHandler getQuests() {
        return RequestHandler.of("getQuests", this::list);
    }

    /**
     * Takes the reward of a completed quest (the daily bonus too, "daily_bonus"): 404 when the quest is not on the boards of the user (any more),
     * 409 when it is not completed or already claimed.
     */
    @Produces
    RequestHandler claimQuest() {
        return RequestHandler.of("claimQuest", this::claim);
    }

    private Uni<Object> list(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        if (publicKey == null || privateKey == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return quests.list(publicKey, privateKey).map(state -> state == null
                ? Protocol.unauthorized()
                : Protocol.reply(200, "OK", quests.toMap(state)));
    }

    private Uni<Object> claim(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        String questId = Protocol.text(params, "quest");
        if (publicKey == null || privateKey == null || questId == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return quests.claim(publicKey, privateKey, questId).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_FOUND -> Protocol.reply(404, "Quest not found");
            case NOT_COMPLETED -> Protocol.reply(409, "The quest is not completed");
            case ALREADY_CLAIMED -> Protocol.reply(409, "The quest is already claimed");
            case OK -> {
                Map<String, Object> reward = new LinkedHashMap<>();
                reward.put("money", result.quest().money());
                reward.put("gems", result.quest().gems());
                reward.put("items", result.items().stream().map(ItemInfo::toMap).toList());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("quest", result.quest().id());
                payload.put("money", result.money());
                payload.put("gems", result.gems());
                payload.put("reward", reward);
                payload.put("quests", quests.toMap(result.state()));
                yield Protocol.reply(200, "OK", payload);
            }
        });
    }
}
