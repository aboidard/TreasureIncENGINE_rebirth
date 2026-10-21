package org.bogomips.treasureInc.engine.item;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;
import org.bogomips.treasureInc.engine.user.UserService;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Item request types: validates params, calls {@link ItemService} and shapes the reply. */
@ApplicationScoped
public class ItemEndpoints {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int MAX_GENERATED_ITEMS = 100;
    static final int MAX_SOLD_ITEMS = 1000;

    private final ItemService items;
    private final UserService users;

    public ItemEndpoints(ItemService items, UserService users) {
        this.items = items;
        this.users = users;
    }

    /** Public listing: only the public key is needed (it is already public, like the leaderboard). */
    @Produces
    RequestHandler getItems() {
        return RequestHandler.of("getItems", params -> list(params, (publicKey, p) -> Uni.createFrom().item(true)));
    }

    /** Same listing, but the private key must match. */
    @Produces
    RequestHandler getPrivateItems() {
        return RequestHandler.of("getPrivateItems", params -> list(params, this::ownsKey));
    }

    @Produces
    RequestHandler generateItemsForUser() {
        return RequestHandler.of("generateItemsForUser", this::generate);
    }

    @Produces
    RequestHandler sellItems() {
        return RequestHandler.of("sellItems", this::sell);
    }

    /** {@code authorize} emits true when the caller is allowed to read the items of the public key. */
    private Uni<Object> list(JsonNode params, BiFunction<String, JsonNode, Uni<Boolean>> authorize) {
        String publicKey = Protocol.publicKey(params);
        Integer page = Protocol.integer(params, "page", 1);
        Integer limit = Protocol.integer(params, "limit", DEFAULT_LIMIT);
        if (publicKey == null || page == null || limit == null || page < 1 || limit < 1 || limit > MAX_LIMIT) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return authorize.apply(publicKey, params).flatMap(ok -> {
            if (!ok) {
                return Uni.createFrom().item(Protocol.unauthorized());
            }
            return items.page(publicKey, page, limit).map(result -> {
                if (result == null) {
                    return Protocol.reply(404, "Not Found");
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("items", result.items().stream().map(ItemInfo::toMap).toList());
                payload.put("page", result.page());
                payload.put("limit", result.limit());
                payload.put("total", result.total());
                return Protocol.reply(200, "OK", payload);
            });
        });
    }

    private Uni<Boolean> ownsKey(String publicKey, JsonNode params) {
        String privateKey = Protocol.text(params, "private_key");
        if (privateKey == null) {
            return Uni.createFrom().item(false);
        }
        return users.authenticate(publicKey, privateKey).map(u -> u != null);
    }

    private Uni<Object> generate(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        Integer nb = Protocol.integer(params, "nb", 1);
        if (publicKey == null || privateKey == null || nb == null || nb < 1 || nb > MAX_GENERATED_ITEMS) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return items.generate(publicKey, privateKey, nb).map(generated -> {
            if (generated == null) {
                return Protocol.unauthorized();
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("items", generated.stream().map(ItemInfo::toMap).toList());
            return Protocol.reply(200, "OK", payload);
        });
    }

    private Uni<Object> sell(JsonNode params) {
        String publicKey = Protocol.publicKey(params);
        String privateKey = Protocol.text(params, "private_key");
        List<Integer> ids = ids(params == null ? null : params.get("items"));
        if (publicKey == null || privateKey == null || ids == null) {
            return Uni.createFrom().item(Protocol.badRequest());
        }
        return items.sell(publicKey, privateKey, ids).map(result -> switch (result.status()) {
            case UNAUTHORIZED -> Protocol.unauthorized();
            case NOT_FOUND -> Protocol.reply(404, "Items not found");
            case OK -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("totalPrice", result.totalPrice());
                payload.put("money", result.money());
                yield Protocol.reply(200, "OK", payload);
            }
        });
    }

    /** Distinct item ids, or null if the list is missing, empty, too long or malformed. */
    private static List<Integer> ids(JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty() || list.size() > MAX_SOLD_ITEMS) {
            return null;
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (JsonNode item : list) {
            JsonNode id = item.get("id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToInt()) {
                return null;
            }
            ids.add(id.intValue());
        }
        return List.copyOf(ids);
    }
}
