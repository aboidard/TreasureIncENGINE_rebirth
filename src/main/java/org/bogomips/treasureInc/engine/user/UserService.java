package org.bogomips.treasureInc.engine.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/** Use cases around users: create, log in, authenticate and keep the Redis "logged users" cache coherent. */
@ApplicationScoped
public class UserService {

    static final int PUBLIC_KEY_LENGTH = 11;
    static final int PRIVATE_KEY_LENGTH = 30;
    private static final int MAX_KEY_ATTEMPTS = 5;
    private static final String DIGITS = "0123456789";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    // SecureRandom instead of Math.random(): these keys are credentials.
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Repository repository;
    private final Cache cache;
    private final Lock lock;
    private final int defaultMoney;

    public UserService(Repository repository, Cache cache, Lock lock,
                       @ConfigProperty(name = "engine.users.default-money", defaultValue = "10000") int defaultMoney) {
        this.repository = repository;
        this.cache = cache;
        this.lock = lock;
        this.defaultMoney = defaultMoney;
    }

    /** Serializes read-modify-write sequences on one user's Redis data. Not reentrant. */
    public <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action) {
        return lock.withLock(publicKey, action);
    }

    /** Persists a new user and logs it in the cache. Fails with {@link KeyGenerationException} if no free key is found. */
    public Uni<CreatedUser> create() {
        return createWithFreeKey(MAX_KEY_ATTEMPTS).flatMap(created ->
                cache.put(created.user().publicKey(), freshSession(created.user(), CachedSession.hashKey(created.privateKey())))
                        .replaceWith(created));
    }

    /** Logs the user in (cache first, database otherwise); emits null when the credentials are wrong. */
    public Uni<UserInfo> login(String publicKey, String privateKey) {
        return lock.withLock(publicKey, () -> loginLocked(publicKey, privateKey));
    }

    private Uni<UserInfo> loginLocked(String publicKey, String privateKey) {
        String keyHash = CachedSession.hashKey(privateKey);
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
            boolean matches = MessageDigest.isEqual(CachedSession.hashKey(privateKey).getBytes(StandardCharsets.UTF_8),
                    cached.keyHash().getBytes(StandardCharsets.UTF_8));
            return Uni.createFrom().item(matches ? cached.user() : null);
        });
    }

    /** Like {@link #authenticate} but guarantees a cached session afterwards. Call it inside {@link #withLock}. */
    public Uni<UserInfo> openSession(String publicKey, String privateKey) {
        return cache.get(publicKey).flatMap(cached -> {
            if (cached != null) {
                return refreshIfMatching(publicKey, cached, CachedSession.hashKey(privateKey));
            }
            return repository.authenticate(publicKey, privateKey).flatMap(user -> user == null
                    ? Uni.createFrom().nullItem()
                    : cache.put(publicKey, freshSession(user, CachedSession.hashKey(privateKey))).replaceWith(user));
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
        String publicKey = randomKey(DIGITS, PUBLIC_KEY_LENGTH);
        String privateKey = randomKey(ALPHANUMERIC, PRIVATE_KEY_LENGTH);
        return repository.exists(publicKey).flatMap(taken -> taken
                ? createWithFreeKey(attemptsLeft - 1)
                : repository.create(publicKey, privateKey, defaultMoney).map(u -> new CreatedUser(u, privateKey)));
    }

    private static CachedSession freshSession(UserInfo user, String keyHash) {
        return new CachedSession(user, Instant.now().toString(), keyHash);
    }

    private static String randomKey(String characters, int length) {
        StringBuilder sb = new StringBuilder(length);
        RANDOM.ints(length, 0, characters.length()).forEach(i -> sb.append(characters.charAt(i)));
        return sb.toString();
    }

    // ---- values

    /** A freshly created user together with its private key, which is only ever available at creation. */
    public record CreatedUser(UserInfo user, String privateKey) {
    }

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

        /** The keyHash stored for a private key (hex SHA-256). */
        public static String hashKey(String privateKey) {
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(privateKey.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
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

    /** No unused public key could be generated within the allowed number of attempts. */
    public static class KeyGenerationException extends RuntimeException {
        public KeyGenerationException() {
            super("Could not generate a unique public key");
        }
    }

    public static class LockTimeoutException extends RuntimeException {
        public LockTimeoutException(String publicKey) {
            super("Another request is already processing user " + publicKey + ", try again");
        }
    }

    // ---- ports, implemented by the Panache and Redis adapters

    public interface Repository {
        /** Updates last login and returns the user if the credentials match, null otherwise. */
        Uni<UserInfo> login(String publicKey, String privateKey);

        /** Checks the credentials without any update; null if they do not match. */
        Uni<UserInfo> authenticate(String publicKey, String privateKey);

        Uni<Boolean> exists(String publicKey);

        Uni<UserInfo> create(String publicKey, String privateKey, int money);

        /** Writes the balance held in Redis. */
        Uni<Void> updateMoney(int userId, int money);
    }

    public interface Cache {
        Uni<CachedSession> get(String publicKey);

        Uni<Void> put(String publicKey, CachedSession session);

        /**
         * Streams every session incrementally (HSCAN) instead of loading them all at once.
         * Entries may be stale or repeated, so re-read one with {@link #get} before acting on it.
         */
        Multi<CachedSession> scan();

        Uni<Void> evict(String publicKey);
    }

    /**
     * Mutual exclusion per user across every engine instance.
     * Read-modify-write sequences on a user's Redis data (session, items) must run inside it.
     * Not reentrant: do not nest calls for the same user.
     */
    public interface Lock {
        /**
         * Runs {@code action} once the lock is held and releases it when the returned Uni terminates.
         * Fails with {@link LockTimeoutException} if the lock cannot be obtained in time.
         */
        <T> Uni<T> withLock(String publicKey, Supplier<Uni<T>> action);
    }
}
