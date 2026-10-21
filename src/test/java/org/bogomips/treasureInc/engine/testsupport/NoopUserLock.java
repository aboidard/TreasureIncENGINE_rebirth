package org.bogomips.treasureInc.engine.testsupport;

import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.user.application.UserLock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Single-threaded tests need no mutual exclusion; records the keys it was asked to lock. */
public class NoopUserLock implements UserLock {

    public final List<String> locked = new ArrayList<>();

    @Override
    public <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action) {
        locked.add(publicKey);
        return Uni.createFrom().<T>deferred(action::get);
    }
}
