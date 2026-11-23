package org.bogomips.treasureInc.engine.settings;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.bogomips.treasureInc.engine.settings.Settings.Maintenance;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** The only row ({@link #ID}) of the table app_settings. The times are UTC. */
@Entity
@Table(name = "app_settings", schema = "public")
public class AppSettings extends PanacheEntityBase {

    static final int ID = 1;

    @Id
    public Integer id;

    public Boolean maintenance;

    @Column(name = "maintenancereason")
    public String maintenanceReason;

    /** Null: from the moment the maintenance is switched on. */
    @Column(name = "maintenancestart")
    public LocalDateTime maintenanceStart;

    /** Null: until it is switched off. */
    @Column(name = "maintenanceend")
    public LocalDateTime maintenanceEnd;

    @Column(name = "minclientversion")
    public String minClientVersion;

    @Column(name = "updatedat")
    public LocalDateTime updatedAt;

    static AppSettings fresh() {
        AppSettings s = new AppSettings();
        s.id = ID;
        s.maintenance = false;
        s.minClientVersion = Settings.DEFAULT.minClientVersion();
        return s;
    }

    Settings toSettings() {
        Maintenance m = new Maintenance(Boolean.TRUE.equals(maintenance), maintenanceReason, instant(maintenanceStart),
                instant(maintenanceEnd));
        return new Settings(m, minClientVersion == null ? Settings.DEFAULT.minClientVersion() : minClientVersion,
                instant(updatedAt));
    }

    static LocalDateTime utc(Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
