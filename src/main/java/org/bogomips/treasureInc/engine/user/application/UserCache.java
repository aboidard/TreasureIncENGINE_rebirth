package org.bogomips.treasureInc.engine.user.application;

import io.smallrye.mutiny.Uni;

public interface UserCache {
    Uni<CachedSession> get(String publicKey);

    Uni<Void> put(String publicKey, CachedSession session);

    /**
     * Streams every session incrementally (HSCAN) instead of loading them all at once.
     * Entries may be stale or repeated, so re-read one with {@link #get} before acting on it.
     */
    io.smallrye.mutiny.Multi<CachedSession> scan();

    Uni<Void> evict(String publicKey);
}
