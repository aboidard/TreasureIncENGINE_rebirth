package org.bogomips.treasureInc.engine.events;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** A row of the table game_events. The times are UTC. */
@Entity
@Table(name = "game_events", schema = "public")
public class EventRow extends PanacheEntityBase {

    @Id
    public String code;

    public String name;

    public String description;

    public String image;

    public Integer level;

    public Integer floors;

    @Column(name = "rewardgems")
    public Integer rewardGems;

    @Column(name = "rewardmoney")
    public Integer rewardMoney;

    @Column(name = "rewarditems")
    public Integer rewardItems;

    @Column(name = "maxperday")
    public Integer maxPerDay;

    @Column(name = "startat")
    public LocalDateTime startAt;

    @Column(name = "endat")
    public LocalDateTime endAt;

    public Boolean enabled;

    @Column(name = "sortorder")
    public Integer sortOrder;

    @Column(name = "updatedat")
    public LocalDateTime updatedAt;

    void copy(GameEvent e) {
        code = e.code();
        name = e.name();
        description = e.description();
        image = e.image();
        level = e.level();
        floors = e.floors();
        rewardGems = e.rewardGems();
        rewardMoney = e.rewardMoney();
        rewardItems = e.rewardItems();
        maxPerDay = e.maxPerDay();
        startAt = utc(e.start());
        endAt = utc(e.end());
        enabled = e.enabled();
        sortOrder = e.sortOrder();
        updatedAt = utc(e.updatedAt());
    }

    GameEvent toEvent() {
        return new GameEvent(code, name, description, image, or(level, 1), or(floors, GameEvent.MAX_FLOORS),
                or(rewardGems, 0), or(rewardMoney, 0), or(rewardItems, 0), or(maxPerDay, 0), instant(startAt),
                instant(endAt), Boolean.TRUE.equals(enabled), or(sortOrder, 0), instant(updatedAt));
    }

    private static int or(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static LocalDateTime utc(Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
