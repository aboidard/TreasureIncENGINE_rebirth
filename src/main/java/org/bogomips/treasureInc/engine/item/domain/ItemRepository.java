package org.bogomips.treasureInc.engine.item.domain;

import io.smallrye.mutiny.Uni;

import java.util.List;

public interface ItemRepository {
    /** All items of the user ordered by id; ItemList.unknownUser() when the public key does not exist. */
    Uni<ItemList> findByPublicKey(String publicKey);

    /** Atomically deletes the items (all must belong to the user) and credits their price to the user's money. */
    /** Sells database items (ids may be empty); extraMoney is the value of Redis-only items sold alongside. */
    Uni<SellResult> sell(String publicKey, String privateKey, List<Integer> ids, int extraMoney);

    /** Inserts Redis-only items for the user; emits their new database ids, in the same order. */
    Uni<List<Integer>> insertAll(int userId, List<ItemInfo> items);

    Uni<Void> deleteByIds(int userId, List<Integer> ids);
}
