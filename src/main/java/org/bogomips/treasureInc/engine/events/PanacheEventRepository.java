package org.bogomips.treasureInc.engine.events;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
class PanacheEventRepository implements EventService.Repository {

    @Override
    public Uni<List<GameEvent>> load() {
        return Panache.withSession(() -> EventRow.<EventRow>listAll())
                .map(rows -> rows.stream().map(EventRow::toEvent).toList());
    }

    @Override
    public Uni<GameEvent> save(GameEvent event) {
        return Panache.withTransaction(() -> EventRow.<EventRow>findById(event.code()).flatMap(found -> {
            EventRow row = found == null ? new EventRow() : found;
            row.copy(event);
            return found == null ? row.<EventRow>persist() : Uni.createFrom().item(row);
        }).map(EventRow::toEvent));
    }

    @Override
    public Uni<Boolean> delete(String code) {
        return Panache.withTransaction(() -> EventRow.deleteById(code));
    }
}
