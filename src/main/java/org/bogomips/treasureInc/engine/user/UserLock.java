package org.bogomips.treasureInc.engine.user;

import io.smallrye.mutiny.Uni;

import java.util.function.Supplier;

/**
 * Mutual exclusion per user across every engine instance.
 * Read-modify-write sequences on a user's Redis data (session, items) must run inside it.
 * Not reentrant: do not nest calls for the same user.
 */
public interface UserLock {

    /**
     * Runs {@code action} once the lock is held and releases it when the returned Uni terminates.
     * Fails with {@link LockTimeoutException} if the lock cannot be obtained in time.
     */
    <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action);
}
