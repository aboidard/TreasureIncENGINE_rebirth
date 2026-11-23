package org.bogomips.treasureInc.engine.events;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bogomips.treasureInc.engine.messaging.Protocol;
import org.bogomips.treasureInc.engine.messaging.RequestHandler;

import java.time.Instant;
import java.util.Map;

/**
 * Administration request types of the events, sent by Maestro straight on the Redis request channel. The events of
 * the game (getEvents, startEventExpedition) are served by the expeditions.
 */
@ApplicationScoped
class EventEndpoints {

    private final EventService events;

    EventEndpoints(EventService events) {
        this.events = events;
    }

    /** Every event, open or not, read again from the database: {"events": [GameEvent#toMap]}. */
    @Produces
    RequestHandler listEvents() {
        return RequestHandler.admin("listEvents", params -> events.reload().map(list -> {
            Instant now = events.now();
            return Protocol.reply(200, "OK", Map.of("events", list.stream().map(e -> e.toMap(now)).toList()));
        }));
    }

    /** params: an event (see {@link GameEvent#parse}): creates it, or replaces the one of its code. 400 if wrong. */
    @Produces
    RequestHandler saveEvent() {
        return RequestHandler.admin("saveEvent", params -> {
            GameEvent event;
            try {
                event = GameEvent.parse(params);
            } catch (IllegalArgumentException e) {
                return Uni.createFrom().item(Protocol.reply(400, "Bad Request: " + e.getMessage()));
            }
            return events.save(event).map(saved -> Protocol.reply(200, "OK", saved.toMap(events.now())));
        });
    }

    /** params: {code}: deletes the event; 404 when there is none. */
    @Produces
    RequestHandler deleteEvent() {
        return RequestHandler.admin("deleteEvent", params -> {
            String code = Protocol.text(params, "code");
            if (code == null) {
                return Uni.createFrom().item(Protocol.reply(400, "Bad Request: code is required"));
            }
            return events.delete(code).map(deleted -> deleted
                    ? Protocol.reply(200, "OK", Map.of("code", code))
                    : Protocol.reply(404, "Event not found"));
        });
    }
}
