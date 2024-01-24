package delivery;

import common.XServedByHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.vertx.ext.web.impl.Utils.canUpgradeToWebsocket;
import static java.time.temporal.ChronoUnit.*;

public class DeliveryVerticle extends AbstractVerticle {

  private static final Duration PREPARATION = Duration.of(90, SECONDS);
  private static final Duration EXPIRATION = Duration.of(2, MINUTES);

  private final List<JsonObject> deliveries = new ArrayList<>();
  private final Set<ServerWebSocket> sockets = new HashSet<>();

  @Override
  public void start(Promise<Void> startPromise) {
    vertx.setPeriodic(2000, l -> {
      Instant now = Instant.now();
      cleanDeliveries(now);
      pushUpdates(now);
    });

    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(conf -> {

      String serverHost = conf.getString("serverHost", "0.0.0.0");
      Integer serverPort = conf.getInteger("serverPort", 8083);

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create(LoggerFormat.TINY))
        .handler(new XServedByHandler("delivery"))
        .handler(ResponseTimeHandler.create());

      router.post("/delivery/add")
        .handler(BodyHandler.create().setDeleteUploadedFilesOnEnd(true))
        .handler(this::deliveryAdd);
      router.route("/delivery/updates").handler((ProtocolUpgradeHandler) this::deliveryUpdates);

      router.get("/health*").handler(HealthCheckHandler.create(vertx));

      router.route().failureHandler(ErrorHandler.create(vertx));

      return vertx.createHttpServer(new HttpServerOptions().setHost(serverHost).setPort(serverPort))
        .requestHandler(router)
        .listen()
        .<Void>mapEmpty();

    }).onComplete(startPromise);
  }

  private void cleanDeliveries(Instant now) {
    Instant limit = now.minus(EXPIRATION);
    deliveries.removeIf(delivery -> delivery.getInstant("createdOn").plus(PREPARATION).isBefore(limit));
  }

  private void deliveryAdd(RoutingContext rc) {
    JsonObject delivery = rc.body().asJsonObject();
    if (delivery == null) {
      rc.fail(400);
      return;
    }

    String firstName = delivery.getString("firstName");
    if (firstName == null || firstName.isBlank()) {
      rc.fail(400);
      return;
    }

    String lastName = delivery.getString("lastName");
    if (lastName == null || lastName.isBlank()) {
      rc.fail(400);
      return;
    }

    delivery.put("createdOn", Instant.now());

    deliveries.add(delivery);

    rc.response().end();
  }

  private void deliveryUpdates(RoutingContext rc) {
    if (canUpgradeToWebsocket(rc.request())) {
      Future<ServerWebSocket> socketFuture = rc.request().toWebSocket();
      socketFuture
        .onFailure(rc::fail)
        .onSuccess(socket -> {
          socket.closeHandler(v -> sockets.remove(socket));
          sockets.add(socket);
        });
    } else {
      rc.next();
    }
  }

  private void pushUpdates(Instant now) {
    List<JsonObject> message = new ArrayList<>(deliveries.size());
    for (JsonObject delivery : deliveries) {

      delivery.remove("order");

      long elapsed = delivery.getInstant("createdOn").until(now, MILLIS);
      long completion = elapsed >= PREPARATION.toMillis() ? 100 : Math.ceilDiv(100 * elapsed, PREPARATION.toMillis());
      delivery.put("completion", completion);
      message.add(delivery);
    }

    for (ServerWebSocket socket : sockets) {
      socket.writeTextMessage(new JsonArray(message).encode());
    }
  }
}
