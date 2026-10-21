package org.bogomips.treasureInc.engine.item;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.bogomips.treasureInc.engine.item.ItemService.ItemList;
import org.bogomips.treasureInc.engine.item.ItemService.SellResult;
import org.bogomips.treasureInc.engine.user.User;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@ApplicationScoped
public class PanacheItemRepository implements ItemService.Repository {

    @Override
    public Uni<ItemList> findByPublicKey(String publicKey) {
        return Panache.withSession(() ->
                User.<User>find("publicKey", publicKey).firstResult().flatMap(user -> {
                    if (user == null) {
                        return Uni.createFrom().item(ItemList.unknownUser());
                    }
                    return Item.<Item>list("userId = ?1 order by id", user.id)
                            .map(items -> new ItemList(true, items.stream().map(Item::toInfo).toList()));
                }));
    }

    @Override
    public Uni<List<Integer>> insertAll(int userId, List<ItemInfo> items) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Item> entities = items.stream().map(i -> {
            Item e = new Item();
            e.name = i.name();
            e.description = i.resolvedDescription();
            e.graphics = i.graphics();
            e.price = i.price();
            e.rarity = i.rarity();
            e.userId = userId;
            e.createdAt = now;
            e.updatedAt = now;
            return e;
        }).toList();
        return Panache.withTransaction(() -> Item.persist(entities))
                .map(v -> entities.stream().map(e -> e.id).toList());
    }

    @Override
    public Uni<Void> deleteByIds(int userId, List<Integer> ids) {
        return ids.isEmpty() ? Uni.createFrom().voidItem()
                : Panache.withTransaction(() -> Item.delete("userId = ?1 and id in ?2", userId, ids)).replaceWithVoid();
    }

    @Override
    public Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids, int extraMoney) {
        return Panache.withTransaction(() ->
                User.<User>find("publicKey = ?1 and privateKey = ?2", publicKey, privateKey)
                        .withLock(LockModeType.PESSIMISTIC_WRITE)
                        .firstResult()
                        .flatMap(user -> {
                            if (user == null) {
                                return Uni.createFrom().item(SellResult.of(SellResult.Status.UNAUTHORIZED));
                            }
                            Uni<List<Item>> found = ids.isEmpty() ? Uni.createFrom().item(List.<Item>of())
                                    : Item.<Item>list("userId = ?1 and id in ?2", user.id, ids);
                            return found.flatMap(items -> {
                                if (items.size() != ids.size()) {
                                    return Uni.createFrom().item(SellResult.of(SellResult.Status.NOT_FOUND));
                                }
                                int total = extraMoney;
                                for (Item item : items) {
                                    total = Math.addExact(total, item.price == null ? 0 : item.price);
                                }
                                int money = Math.addExact(user.money == null ? 0 : user.money, total);
                                user.money = money;
                                user.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
                                int sold = total;
                                Uni<?> deletion = ids.isEmpty() ? Uni.createFrom().voidItem()
                                        : Item.delete("userId = ?1 and id in ?2", user.id, ids);
                                return deletion
                                        .replaceWith(new SellResult(SellResult.Status.OK, sold, money, ids));
                            });
                        }));
    }
}
