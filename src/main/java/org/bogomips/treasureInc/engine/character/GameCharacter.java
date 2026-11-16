package org.bogomips.treasureInc.engine.character;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/** A character of a user ("Character" alone would hide java.lang.Character). */
@Entity
@Table(name = "characters", schema = "public")
public class GameCharacter extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "characters_id_seq")
    @SequenceGenerator(name = "characters_id_seq", sequenceName = "characters_id_seq", schema = "public", allocationSize = 1)
    public Integer id;

    @Column(name = "user_id")
    public Integer userId;

    /** Number of the character in the catalog (see CharacterCatalog), from 1. */
    public Integer num;

    /** Item id by slot number, e.g. {"2": 812, "3": 640}; see Equipment.Slot for the numbers. */
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Integer> equipment;

    /** How many times the user obtained this character: 1, plus one for each duplicate drawn. */
    public Integer copies;

    @Column(name = "createdat")
    public LocalDateTime createdAt;

    @Column(name = "updatedat")
    public LocalDateTime updatedAt;
}
