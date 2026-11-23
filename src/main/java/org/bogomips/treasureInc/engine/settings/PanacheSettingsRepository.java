package org.bogomips.treasureInc.engine.settings;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import org.bogomips.treasureInc.engine.settings.Settings.Maintenance;

import java.time.Instant;
import java.util.function.Consumer;

@ApplicationScoped
class PanacheSettingsRepository implements SettingsService.Repository {

    @Override
    public Uni<Settings> load() {
        return Panache.withSession(() -> AppSettings.<AppSettings>findById(AppSettings.ID))
                .map(row -> row == null ? Settings.DEFAULT : row.toSettings());
    }

    @Override
    public Uni<Settings> saveMaintenance(Maintenance m, Instant at) {
        return update(at, row -> {
            row.maintenance = m.enabled();
            row.maintenanceReason = m.reason();
            row.maintenanceStart = AppSettings.utc(m.start());
            row.maintenanceEnd = AppSettings.utc(m.end());
        });
    }

    @Override
    public Uni<Settings> saveMinClientVersion(String version, Instant at) {
        return update(at, row -> row.minClientVersion = version);
    }

    /** Locks the row (created when missing) so that two changes made at once are both kept. */
    private Uni<Settings> update(Instant at, Consumer<AppSettings> change) {
        return Panache.withTransaction(() -> AppSettings.<AppSettings>findById(AppSettings.ID, LockModeType.PESSIMISTIC_WRITE)
                .flatMap(found -> {
                    AppSettings row = found == null ? AppSettings.fresh() : found;
                    change.accept(row);
                    row.updatedAt = AppSettings.utc(at);
                    return found == null ? row.<AppSettings>persist() : Uni.createFrom().item(row);
                })
                .map(AppSettings::toSettings));
    }
}
