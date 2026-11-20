package org.bogomips.treasureInc.engine.gift;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.bogomips.treasureInc.engine.gift.GiftService.GiftInfo;
import org.bogomips.treasureInc.engine.gift.GiftService.Reward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** A gift waiting in the gift box of a user, or already taken (claimedAt). The times are UTC. */
@Entity
@Table(name = "gifts", schema = "public")
public class Gift extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gifts_id_seq")
    @SequenceGenerator(name = "gifts_id_seq", sequenceName = "gifts_id_seq", schema = "public", allocationSize = 1)
    public Integer id;

    @Column(name = "user_id")
    public Integer userId;

    public String title;

    public Integer money;

    public Integer gems;

    /** How many items the gift generates when it is taken. */
    public Integer items;

    /** The rarity of these items; null when each one is drawn as in a chest. */
    public String rarity;

    /** The sending the gift comes from: every gift of one sending shares it. */
    public String batch;

    @Column(name = "createdat")
    public LocalDateTime createdAt;

    /** Null when the gift never expires. */
    @Column(name = "expiresat")
    public LocalDateTime expiresAt;

    @Column(name = "claimedat")
    public LocalDateTime claimedAt;

    GiftInfo toInfo() {
        return new GiftInfo(id, title, new Reward(orZero(money), orZero(gems), orZero(items), rarity),
                iso(createdAt), iso(expiresAt));
    }

    private static int orZero(Integer n) {
        return n == null ? 0 : n;
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
    }
}
