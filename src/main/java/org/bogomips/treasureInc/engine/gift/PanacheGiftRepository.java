package org.bogomips.treasureInc.engine.gift;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.bogomips.treasureInc.engine.gift.GiftService.ClaimOutcome;
import org.bogomips.treasureInc.engine.gift.GiftService.Pending;
import org.bogomips.treasureInc.engine.gift.GiftService.Reward;
import org.bogomips.treasureInc.engine.gift.GiftService.Target;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
class PanacheGiftRepository implements GiftService.Repository {

    private static final String PENDING = "userId = ?1 and claimedAt is null and (expiresAt is null or expiresAt > ?2)";

    @Override
    public Uni<Pending> pending(int userId, Instant now, int limit) {
        LocalDateTime at = utc(now);
        return Panache.withSession(() -> Gift.count(PENDING, userId, at).flatMap(total ->
                Gift.<Gift>find(PENDING + " order by id desc", userId, at).page(0, limit).list()
                        .map(gifts -> new Pending((int) (long) total, gifts.stream().map(Gift::toInfo).toList()))));
    }

    @Override
    public Uni<ClaimOutcome> claim(int userId, int giftId, Instant now) {
        LocalDateTime at = utc(now);
        return Panache.withTransaction(() -> Gift.<Gift>find("id = ?1 and userId = ?2", giftId, userId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult()
                .map(gift -> {
                    if (gift == null) {
                        return ClaimOutcome.of(ClaimOutcome.Status.NOT_FOUND);
                    }
                    if (gift.claimedAt != null) {
                        return ClaimOutcome.of(ClaimOutcome.Status.ALREADY_CLAIMED);
                    }
                    if (gift.expiresAt != null && !gift.expiresAt.isAfter(at)) {
                        return ClaimOutcome.of(ClaimOutcome.Status.EXPIRED);
                    }
                    gift.claimedAt = at;
                    return new ClaimOutcome(ClaimOutcome.Status.OK, gift.toInfo());
                }));
    }

    /**
     * One INSERT ... SELECT on the users: the players are never loaded. The activity of a player is the latest of
     * its last login and of the last write of its session to the database (a session that lasts does not log in
     * again). Every parameter is cast: in a SELECT list PostgreSQL cannot guess its type, and the values that may be
     * null are written as literals.
     */
    @Override
    public Uni<Integer> send(Target target, String title, Reward reward, Duration expiresIn, String batch, Instant now) {
        LocalDateTime at = utc(now);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("INSERT INTO public.gifts")
                .append(" (user_id, title, money, gems, items, rarity, batch, createdat, expiresat)")
                .append(" SELECT u.id, CAST(:title AS varchar), CAST(:money AS integer), CAST(:gems AS integer),")
                .append(" CAST(:items AS integer), ")
                .append(reward.rarity() == null ? "NULL" : "CAST(:rarity AS varchar)")
                .append(", CAST(:batch AS varchar), CAST(:now AS timestamp), ")
                .append(expiresIn == null ? "NULL" : "CAST(:expiresAt AS timestamp)")
                .append(" FROM public.users u WHERE true");
        params.put("title", title);
        params.put("money", reward.money());
        params.put("gems", reward.gems());
        params.put("items", reward.items());
        if (reward.rarity() != null) {
            params.put("rarity", reward.rarity());
        }
        params.put("batch", batch);
        params.put("now", at);
        if (expiresIn != null) {
            params.put("expiresAt", utc(now.plus(expiresIn)));
        }
        String activity = "GREATEST(u.lastlogin, u.updatedat)";
        if (target.activeDays() != null) {
            sql.append(" AND ").append(activity).append(" >= CAST(:activeSince AS timestamp)");
            params.put("activeSince", utc(now.minus(Duration.ofDays(target.activeDays()))));
        }
        if (target.inactiveDays() != null) {
            sql.append(" AND ").append(activity).append(" < CAST(:inactiveSince AS timestamp)");
            params.put("inactiveSince", utc(now.minus(Duration.ofDays(target.inactiveDays()))));
        }
        if (target.createdDays() != null) {
            sql.append(" AND u.createdat >= CAST(:createdSince AS timestamp)");
            params.put("createdSince", utc(now.minus(Duration.ofDays(target.createdDays()))));
        }
        if (target.publicKeys() != null) {
            sql.append(" AND u.public_key IN (:keys)");
            params.put("keys", target.publicKeys());
        }
        return Panache.withTransaction(() -> Panache.getSession().flatMap(session -> {
            var query = session.createNativeQuery(sql.toString());
            params.forEach(query::setParameter);
            return query.executeUpdate();
        }));
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
