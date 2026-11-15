package org.bogomips.treasureInc.engine.character;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.character.CharacterService.CharacterView;
import org.bogomips.treasureInc.engine.character.CharacterService.Result;
import org.bogomips.treasureInc.engine.item.Equipment;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

import java.util.Map;

/** Character request types: validates params, calls {@link CharacterService} and shapes the reply. */
@ApplicationScoped
class CharacterEndpoints {

    private final CharacterService characters;

    CharacterEndpoints(CharacterService characters) {
        this.characters = characters;
    }

    @Produces
    RequestHandler getCharacters() {
        return RequestHandler.of("getCharacters", this::list);
    }

    @Produces
    RequestHandler equipItem() {
        return RequestHandler.of("equipItem", this::equip);
    }

    @Produces
    RequestHandler unequipItem() {
        return RequestHandler.of("unequipItem", this::unequip);
    }

    private Uni<Object> list(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        if (publicKey == null || privateKey == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return characters.list(publicKey, privateKey).map(CharacterEndpoints::reply);
    }

    private Uni<Object> equip(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        Integer num = required(params, "character");
        Integer slot = required(params, "slot");
        Integer item = required(params, "item");
        if (publicKey == null || privateKey == null || item == null || !isValid(num, slot)) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return characters.equip(publicKey, privateKey, num, slot, item).map(CharacterEndpoints::reply);
    }

    private Uni<Object> unequip(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        Integer num = required(params, "character");
        Integer slot = required(params, "slot");
        if (publicKey == null || privateKey == null || !isValid(num, slot)) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return characters.unequip(publicKey, privateKey, num, slot).map(CharacterEndpoints::reply);
    }

    private boolean isValid(Integer num, Integer slot) {
        return num != null && slot != null && characters.isValid(num) && Equipment.Slot.isValid(slot);
    }

    private static Object reply(Result result) {
        return switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_FOUND -> Protocol.reply(404, "Item not found");
            case WRONG_SLOT -> Protocol.reply(400, "The item does not fit this slot");
            case BUSY -> Protocol.reply(409, "The character is on an expedition");
            case OK -> Protocol.reply(200, "OK",
                    Map.of("characters", result.characters().stream().map(CharacterView::toMap).toList()));
        };
    }

    /** Integer field that must be present; null when absent or not an integer. */
    private static Integer required(JsonNode params, String field) {
        return params == null || !params.hasNonNull(field) ? null : Protocol.integer(params, field, 0);
    }
}
