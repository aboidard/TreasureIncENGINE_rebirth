package org.bogomips.treasureInc.engine.item;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "items", schema = "public")
public class Item extends PanacheEntityBase {

    /** Assigned by the engine when the item is generated, from the items_id_seq sequence (see ItemService). */
    @Id
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
