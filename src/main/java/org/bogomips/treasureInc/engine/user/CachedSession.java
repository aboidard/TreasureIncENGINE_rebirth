package org.bogomips.treasureInc.engine.user;


import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A logged-in user as kept in Redis.
 * keyHash lets us check the private key on a cache hit without storing it.
 * lastSync is when the user (and its Redis-only items) was last written to the database;
 * dirty says whether anything changed in Redis since then.
 */
public record CachedSession(
        @JsonProperty("user") UserInfo user,
        @JsonProperty("lastAction") String lastAction,
        @JsonProperty("keyHash") String keyHash,
        @JsonProperty("lastSync") String lastSync,
        @JsonProperty("dirty") boolean dirty) {

    /** A session that is in sync with the database as of its last action. */
    public CachedSession(UserInfo user, String lastAction, String keyHash) {
        this(user, lastAction, keyHash, lastAction, false);
    }

    public CachedSession touched() {
        return new CachedSession(user, Instant.now().toString(), keyHash, lastSync, dirty);
    }

    /** New user data; {@code needsSync} flags it as not yet written to the database (an existing flag is kept). */
    public CachedSession withUser(UserInfo updated, boolean needsSync) {
        return new CachedSession(updated, Instant.now().toString(), keyHash, lastSync, dirty || needsSync);
    }

    public CachedSession markDirty() {
        return dirty ? this : new CachedSession(user, lastAction, keyHash, lastSync, true);
    }

    public CachedSession synced(Instant at, boolean stillDirty) {
        return new CachedSession(user, lastAction, keyHash, at.toString(), stillDirty);
    }

    public Instant lastSyncAt() {
        return lastSync == null ? Instant.EPOCH : Instant.parse(lastSync);
    }

    public Instant lastActionAt() {
        return lastAction == null ? Instant.EPOCH : Instant.parse(lastAction);
    }
}
