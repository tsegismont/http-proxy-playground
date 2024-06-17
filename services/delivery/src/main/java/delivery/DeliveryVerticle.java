package delivery;

import common.XServedByHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.impl.HttpUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.ext.auth.jose.KeyStoreOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.micrometer.PrometheusScrapingHandler;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collector;

import static common.EnvUtil.*;
import static java.time.temporal.ChronoUnit.*;
import static java.util.stream.Collectors.groupingBy;

public class DeliveryVerticle extends AbstractVerticle {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new DeliveryVerticle())
      .onFailure(Throwable::printStackTrace)
      .onSuccess(v -> System.out.println("Deployed DeliveryVerticle"));
  }

  private static final Logger LOG = LogManager.getLogger(DeliveryVerticle.class);

  private static final Duration PREPARATION = Duration.of(90, SECONDS);
  private static final Duration EXPIRATION = Duration.of(2, MINUTES);
  private static final String CREATE_TABLE = """
    CREATE TABLE IF NOT EXISTS delivery
    (
        username   VARCHAR,
        created_on TIMESTAMP,
        value      jsonb
    )""";
  private static final String CLEAN_DELIVERIES = "DELETE FROM delivery WHERE created_on < $1";
  private static final String LOAD_DELIVERIES = """
    SELECT username, value
    FROM delivery
    ORDER BY created_on DESC""";
  private static final String INSERT_DELIVERY = """
    INSERT INTO delivery (username, created_on, value)
    VALUES ($1, $2, $3)""";

  private final Map<ServerWebSocket, String> subscriptions = new HashMap<>();
  private Pool pool;

  @Override
  public void start(Promise<Void> startPromise) {
    PgConnectOptions pgConnectOptions = new PgConnectOptions()
      .setHost(postgresServerHost())
      .setPort(postgresServerPort())
      .setDatabase(postgresServerDatabase())
      .setUser(postgresServerUser())
      .setPassword(postgresServerPassword());

    pool = Pool.pool(vertx, pgConnectOptions, new PoolOptions());

    pool.query(CREATE_TABLE).execute().compose(v -> {

      vertx.setPeriodic(2000, l -> {
        cleanDeliveriesAndPushUpdates();
      });

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create(LoggerFormat.TINY))
        .handler(new XServedByHandler("delivery"))
        .handler(ResponseTimeHandler.create());

      router.route("/metrics").handler(PrometheusScrapingHandler.create());

      JWTAuthOptions authConfig = new JWTAuthOptions()
        .setKeyStore(new KeyStoreOptions()
          .setType("PKCS12")
          .setPath("http-proxy-playground.p12")
          .setPassword("foobar"));

      JWTAuth authProvider = JWTAuth.create(vertx, authConfig);

      router.route("/delivery*").handler(JWTAuthHandler.create(authProvider));

      router.post("/delivery/add")
        .handler(BodyHandler.create().setDeleteUploadedFilesOnEnd(true))
        .handler(this::deliveryAdd);
      router.route("/delivery/updates").handler((ProtocolUpgradeHandler) this::deliveryUpdates);

      router.get("/health*").handler(HealthCheckHandler.create(vertx));

      router.route().failureHandler(ErrorHandler.create(vertx));

      KeyCertOptions keyCertOptions = new PfxOptions()
        .setPath("http-proxy-playground.p12")
        .setPassword("foobar")
        .setAlias("http-proxy-playground");

      HttpServerOptions options = new HttpServerOptions()
        .setHost(serverHost())
        .setPort(serverPort(8446))
        .setUseAlpn(true)
        .setSsl(true)
        .setKeyCertOptions(keyCertOptions)
        .setCompressionSupported(true);

      return vertx.createHttpServer(options)
        .requestHandler(router)
        .listen()
        .<Void>mapEmpty();

    }).onComplete(startPromise);
  }

  private void cleanDeliveriesAndPushUpdates() {
    Instant now = Instant.now();
    Instant limit = now.minus(EXPIRATION).minus(PREPARATION);

    pool.preparedQuery(CLEAN_DELIVERIES).execute(Tuple.of(limit.atOffset(ZoneOffset.UTC).toLocalDateTime())).compose(v -> {

      Collector<Row, ?, Map<String, List<Row>>> collector = groupingBy(row -> row.getString("username"));

      return pool.query(LOAD_DELIVERIES).collecting(collector).execute().onSuccess(res -> {
        if (res.size() > 0) {
          Map<String, List<Row>> map = res.value();

          for (Map.Entry<ServerWebSocket, String> subscription : subscriptions.entrySet()) {

            List<Row> deliveries = map.get(subscription.getValue());
            if (deliveries != null) {
              List<JsonObject> message = new ArrayList<>(deliveries.size());
              for (Row row : deliveries) {
                JsonObject delivery = row.getJsonObject("value");

                delivery.remove("order");

                long elapsed = delivery.getInstant("createdOn").until(now, MILLIS);
                long completion = elapsed >= PREPARATION.toMillis() ? 100 : Math.ceilDiv(100 * elapsed, PREPARATION.toMillis());
                delivery.put("completion", completion);

                message.add(delivery);
              }
              subscription.getKey().writeTextMessage(new JsonArray(message).encode());
            }
          }
        }
      });
    }).onFailure(throwable -> LOG.error("Failure while executing periodic task", throwable));
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

    Instant now = Instant.now();
    delivery.put("createdOn", now);

    pool.preparedQuery(INSERT_DELIVERY)
      .execute(Tuple.of(extractUsername(rc), now.atOffset(ZoneOffset.UTC).toLocalDateTime(), delivery))
      .onComplete(v -> rc.response().end(), rc::fail);
  }

  private void deliveryUpdates(RoutingContext rc) {
    if (HttpUtils.canUpgradeToWebSocket(rc.request())) {
      Future<ServerWebSocket> socketFuture = rc.request().toWebSocket();
      socketFuture
        .onFailure(rc::fail)
        .onSuccess(socket -> {
          socket.closeHandler(v -> subscriptions.remove(socket));
          subscriptions.put(socket, extractUsername(rc));
        });
    } else {
      rc.next();
    }
  }

  private static String extractUsername(RoutingContext rc) {
    return rc.user().get().principal().getJsonObject("sub").getString("username");
  }
}
