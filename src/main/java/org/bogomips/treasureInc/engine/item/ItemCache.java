package org.bogomips.treasureInc.engine.item;


import io.smallrye.mutiny.Uni;

import java.util.Collection;

public interface ItemCache {
    /** Null when nothing is cached for this public key. */
    Uni<ItemList> get(String publicKey);

    Uni<Void> put(String publicKey, ItemList list);

    /** Removes the given item ids from the cached list, if any. */
    /** Stores the list without expiry: used for Redis-only items, which have no database copy. */
    Uni<Void> persist(String publicKey, ItemList list);

    /** Reserves {@code count} consecutive ids for Redis-only items (negative, so they never clash with database ids); returns the first. */
    Uni<Integer> reserveIds(int count);

    Uni<Void> removeIds(String publicKey, Collection<Integer> ids);

    /** Drops the cached list, whatever its expiry. */
    Uni<Void> evict(String publicKey);
}
