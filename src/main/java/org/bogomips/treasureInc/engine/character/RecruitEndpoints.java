package org.bogomips.treasureInc.engine.character;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.character.CharacterCatalog.Portal;
import org.bogomips.treasureInc.engine.character.CharacterCatalog.Rank;
import org.bogomips.treasureInc.engine.character.CharacterService.CharacterView;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recruitment request types: validates params, calls {@link RecruitService} and shapes the reply. */
@ApplicationScoped
class RecruitEndpoints {

    private final RecruitService recruits;

    RecruitEndpoints(RecruitService recruits) {
        this.recruits = recruits;
    }

    /** The portals, their price, their rates and their characters: the same for every user, so no credentials. */
    @Produces
    RequestHandler getPortals() {
        return RequestHandler.of("getPortals", params -> Uni.createFrom().item(Protocol.reply(200, "OK",
                Map.of("portals", CharacterCatalog.portals().stream().map(this::portal).toList()))));
    }

    /** Draws 1 or "multi" characters in a portal: 402 if the balance is too low. */
    @Produces
    RequestHandler recruit() {
        return RequestHandler.of("recruit", this::recruit);
    }

    private Uni<Object> recruit(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        String portalId = Protocol.text(params, "portal");
        Integer count = Protocol.integer(params, "count", 1);
        if (publicKey == null || privateKey == null || portalId == null || count == null
                || !recruits.isValidCount(count)) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        Portal portal = CharacterCatalog.portal(portalId);
        if (portal == null) {
            return Uni.createFrom().item(Protocol.reply(404, "Portal not found"));
        }
        return recruits.recruit(publicKey, privateKey, portal, count).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_ENOUGH_MONEY -> Protocol.reply(402, "Not enough money", Map.of("money", result.money()));
            case OK -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("draws", result.draws().stream().map(RecruitEndpoints::draw).toList());
                payload.put("money", result.money());
                payload.put("characters", result.characters().stream().map(CharacterView::toMap).toList());
                yield Protocol.reply(200, "OK", payload);
            }
        });
    }

    private static Map<String, Object> draw(RecruitService.Draw draw) {
        Map<String, Object> m = new LinkedHashMap<>(draw.character().toMap());
        m.put("isNew", draw.isNew());
        m.put("copies", draw.copies());
        return m;
    }

    private Map<String, Object> portal(Portal portal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", portal.id());
        m.put("name", portal.name());
        m.put("cost", recruits.cost());
        m.put("multi", recruits.multi());
        m.put("rates", rates());
        m.put("characters", portal.characters().stream().map(CharacterCatalog.Info::toMap).toList());
        return m;
    }

    /** From the strongest rank to the weakest. */
    private static List<Map<String, Object>> rates() {
        List<Map<String, Object>> rates = new ArrayList<>();
        for (int i = Rank.values().length - 1; i >= 0; i--) {
            Rank rank = Rank.values()[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank.name());
            m.put("percent", rank.percent());
            rates.add(m);
        }
        return rates;
    }
}
