package org.bogomips.treasureInc.engine.user;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Use cases around users: create, log in, authenticate and keep the Redis "logged users" cache coherent. */
@ApplicationScoped
public class UserService {

    static final int PUBLIC_KEY_LENGTH = 11;
    static final int PRIVATE_KEY_LENGTH = 30;
    private static final int MAX_KEY_ATTEMPTS = 5;

    private final UserRepository repository;
    private final UserCache cache;
    private final UserLock lock;
    private final int defaultMoney;

    public UserService(UserRepository repository, UserCache cache, UserLock lock,
                       @ConfigProperty(name = "engine.users.default-money", defaultValue = "10000") int defaultMoney) {
        this.repository = repository;
        this.cache = cache;
        this.lock = lock;
        this.defaultMoney = defaultMoney;
    }

    /** Serializes read-modify-write sequences on one user's Redis data. Not reentrant. */
    public <T> Uni<T> withLock(String publicKey, java.util.function.Supplier<Uni<T>> action) {
        return lock.withLock(publicKey, action);
    }

    /** Persists a new user and logs it in the cache. Fails with {@link KeyGenerationException} if no free key is found. */
    public Uni<CreatedUser> create() {
        return createWithFreeKey(MAX_KEY_ATTEMPTS).flatMap(created ->
                cache.put(created.user().publicKey(), freshSession(created.user(), Keys.sha256(created.privateKey())))
                        .replaceWith(created));
    }

    /** Logs the user in (cache first, database otherwise); emits null when the credentials are wrong. */
    public Uni<UserInfo> login(String publicKey, String privateKey) {
        return lock.withLock(publicKey, () -> loginLocked(publicKey, privateKey));
    }

    private Uni<UserInfo> loginLocked(String publicKey, String privateKey) {
        String keyHash = Keys.sha256(privateKey);
        return cache.get(publicKey).flatMap(cached -> {
            if (cached != null) {
                return refreshIfMatching(publicKey, cached, keyHash);
            }
            return repository.login(publicKey, privateKey).flatMap(user -> user == null
                    ? Uni.createFrom().nullItem()
                    : cache.put(publicKey, freshSession(user, keyHash)).replaceWith(user));
        });
    }

    /**
     * Checks the credentials without logging in or writing anything (so it is safe outside the lock);
     * emits null when they are wrong.
     */
    public Uni<UserInfo> authenticate(String publicKey, String privateKey) {
        return cache.get(publicKey).flatMap(cached -> {
            if (cached == null) {
                return repository.authenticate(publicKey, privateKey);
            }
            boolean matches = MessageDigest.isEqual(Keys.sha256(privateKey).getBytes(StandardCharsets.UTF_8),
                    cached.keyHash().getBytes(StandardCharsets.UTF_8));
            return Uni.createFrom().item(matches ? cached.user() : null);
        });
    }

    /** Like {@link #authenticate} but guarantees a cached session afterwards. Call it inside {@link #withLock}. */
    public Uni<UserInfo> openSession(String publicKey, String privateKey) {
        return cache.get(publicKey).flatMap(cached -> {
            if (cached != null) {
                return refreshIfMatching(publicKey, cached, Keys.sha256(privateKey));
            }
            return repository.authenticate(publicKey, privateKey).flatMap(user -> user == null
                    ? Uni.createFrom().nullItem()
                    : cache.put(publicKey, freshSession(user, Keys.sha256(privateKey))).replaceWith(user));
        });
    }

    /** Flags the session as holding changes (e.g. Redis-only items) that the next synchronization must write. */
    public Uni<Void> markDirty(String publicKey) {
        return cache.get(publicKey).flatMap(cached -> cached == null || cached.dirty()
                ? Uni.createFrom().voidItem()
                : cache.put(publicKey, cached.markDirty()));
    }

    /**
     * Adds money to the cached session; emits the updated user, or null when the user is not logged in.
     * {@code pendingSync} is true when the database does not have this amount yet.
     */
    public Uni<UserInfo> credit(String publicKey, int amount, boolean pendingSync) {
        return cache.get(publicKey).flatMap(cached -> {
            if (cached == null) {
                return Uni.createFrom().nullItem();
            }
            UserInfo u = cached.user();
            UserInfo updated = new UserInfo(u.id(), u.publicKey(), Math.addExact(u.money(), amount), u.lastLogin(),
                    u.createdAt(), u.updatedAt());
            return cache.put(publicKey, cached.withUser(updated, pendingSync)).replaceWith(updated);
        });
    }

    private Uni<UserInfo> refreshIfMatching(String publicKey, CachedSession cached, String keyHash) {
        boolean matches = MessageDigest.isEqual(keyHash.getBytes(StandardCharsets.UTF_8),
                cached.keyHash().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            return Uni.createFrom().nullItem();
        }
        return cache.put(publicKey, cached.touched()).replaceWith(cached.user());
    }

    private Uni<CreatedUser> createWithFreeKey(int attemptsLeft) {
        if (attemptsLeft == 0) {
            return Uni.createFrom().failure(new KeyGenerationException());
        }
        String publicKey = Keys.generatePublicKey(PUBLIC_KEY_LENGTH);
        String privateKey = Keys.generatePrivateKey(PRIVATE_KEY_LENGTH);
        return repository.exists(publicKey).flatMap(taken -> taken
                ? createWithFreeKey(attemptsLeft - 1)
                : repository.create(publicKey, privateKey, defaultMoney).map(u -> new CreatedUser(u, privateKey)));
    }

    private static CachedSession freshSession(UserInfo user, String keyHash) {
        return new CachedSession(user, Instant.now().toString(), keyHash);
    }
}
