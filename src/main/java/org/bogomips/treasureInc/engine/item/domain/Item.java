package org.bogomips.treasureInc.engine.item.domain;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "items", schema = "public")
public class Item extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "items_id_seq")
    @SequenceGenerator(name = "items_id_seq", sequenceName = "items_id_seq", schema = "public", allocationSize = 1)
    public Integer id;

    public String name;

    public String description;

    public Integer graphics;

    public Integer price;

    public String rarity;

    @Column(name = "user_id")
    public Integer userId;

    @Column(name = "createdat")
    public LocalDateTime createdAt;

    @Column(name = "updatedat")
    public LocalDateTime updatedAt;

    public ItemInfo toInfo() {
        return new ItemInfo(id, name, description, graphics, price, rarity, iso(createdAt), iso(updatedAt));
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
    }
}
