package org.bogomips.treasureInc.engine.messaging;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.pubsub.ReactivePubSubCommands;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.redis.client.RedisConnection;
import io.vertx.mutiny.redis.client.Response;
import io.vertx.redis.client.ResponseType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/** Redis pub/sub adapter: feeds incoming messages to the {@link RequestDispatcher} and publishes the replies. */
@ApplicationScoped
public class RedisRequestListener {

    private static final Logger LOG = Logger.getLogger(RedisRequestListener.class);
    private static final long RESUBSCRIBE_DELAY_MS = 2000;

    private final Vertx vertx;
    private final Redis redis;
    private final ReactivePubSubCommands<String> publisher;
    private final RequestDispatcher dispatcher;
    private final String requestChannel;
    private final String replyChannel;

    private volatile boolean stopped;
    private volatile RedisConnection subscriberConnection;

    public RedisRequestListener(Vertx vertx,
                                Redis redis,
                                ReactiveRedisDataSource dataSource,
                                RequestDispatcher dispatcher,
                                @ConfigProperty(name = "engine.channels.requests") String requestChannel,
                                @ConfigProperty(name = "engine.channels.replies") String replyChannel) {
        this.vertx = vertx;
        this.redis = redis;
        this.publisher = dataSource.pubsub(String.class);
        this.dispatcher = dispatcher;
        this.requestChannel = requestChannel;
        this.replyChannel = replyChannel;
    }

    void onStart(@Observes StartupEvent ev) {
        subscribe();
    }

    void onStop(@Observes ShutdownEvent ev) {
        stopped = true;
        RedisConnection conn = subscriberConnection;
        if (conn != null) {
            conn.close().subscribe().with(v -> { }, e -> LOG.debug("Error closing subscriber connection", e));
        }
    }

    // Own subscriber connection: ignores non-array frames instead of crashing the event loop.
    private void subscribe() {
        redis.connect()
                .flatMap(conn -> {
                    if (stopped) {
                        return conn.close().replaceWith(conn);
                    }
                    subscriberConnection = conn;
                    conn.handler(this::handleFrame);
                    conn.exceptionHandler(t -> LOG.error("Redis subscriber error", t));
                    conn.endHandler(this::resubscribeLater);
                    return RedisAPI.api(conn).subscribe(List.of(requestChannel)).replaceWith(conn);
                })
                .subscribe().with(
                        c -> LOG.infof("Subscribed to %s", requestChannel),
                        e -> {
                            if (!stopped) {
                                LOG.error("Redis subscription failed", e);
                            }
                            resubscribeLater();
                        });
    }

    private void resubscribeLater() {
        if (!stopped) {
            vertx.setTimer(RESUBSCRIBE_DELAY_MS, id -> subscribe());
        }
    }

    void handleFrame(Response r) {
        if (r == null || (r.type() != ResponseType.MULTI && r.type() != ResponseType.PUSH)) {
            if (r != null && r.type() == ResponseType.ERROR) {
                LOG.warnf("Redis error frame on subscriber connection: %s", r);
            } else {
                LOG.debugf("Ignoring non-array redis frame: %s", r == null ? null : r.type());
            }
            return;
        }
        if (r.size() >= 3 && "message".equalsIgnoreCase(r.get(0).toString())) {
            String channel = r.get(1).toString();
            String message = r.get(2).toString();
            LOG.debugf("event=message_received channel=%s bytes=%d thread=%s", channel, message.length(),
                    Thread.currentThread().getName());
            // Hibernate Reactive needs a safe duplicated Vert.x context
            io.vertx.core.Context ctx = VertxContext.getOrCreateDuplicatedContext(vertx.getOrCreateContext().getDelegate());
            VertxContextSafetyToggle.setContextSafe(ctx, true);
            ctx.runOnContext(v -> onMessage(message));
        }
    }

    void onMessage(String message) {
        dispatcher.dispatch(message)
                .onItem().ifNotNull().transformToUni(reply -> publisher.publish(replyChannel, reply.json())
                        .invoke(() -> LOG.debugf("event=reply_published replyId=%s channel=%s bytes=%d",
                                reply.replyId(), replyChannel, reply.json().length())))
                .subscribe().with(
                        n -> { },
                        e -> LOG.errorf(e, "event=message_failed reason=%s", e.toString()));
    }
}
