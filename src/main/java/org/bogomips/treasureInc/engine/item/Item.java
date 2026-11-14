package org.bogomips.treasureInc.engine.item;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

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

    /** Null unless the item is equipable; see {@link Equipment} for the content. */
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> stats;

    public ItemInfo toInfo() {
        return new ItemInfo(id, name, description, graphics, price, rarity, iso(createdAt), iso(updatedAt), null, stats);
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
    }
}
