package org.bogomips.treasureInc.engine.user.domain;

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
@Table(name = "users", schema = "public")
public class User extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "users_id_seq")
    @SequenceGenerator(name = "users_id_seq", sequenceName = "users_id_seq", schema = "public", allocationSize = 1)
    public Integer id;

    @Column(name = "public_key")
    public String publicKey;

    @Column(name = "private_key")
    public String privateKey;

    @Column(name = "createdat")
    public LocalDateTime createdAt;

    @Column(name = "updatedat")
    public LocalDateTime updatedAt;

    @Column(name = "lastlogin")
    public LocalDateTime lastLogin;

    public Integer money;

    public UserInfo toInfo() {
        return new UserInfo(id, publicKey, money == null ? 0 : money, iso(lastLogin), iso(createdAt), iso(updatedAt));
    }

    private static String iso(LocalDateTime t) {
        return t.toInstant(ZoneOffset.UTC).toString();
    }
}
