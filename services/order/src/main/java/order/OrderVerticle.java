package order;

import common.XServedByHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.ext.auth.KeyStoreOptions;
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
import io.vertx.sqlclient.Tuple;

import static common.EnvUtil.*;

public class OrderVerticle extends AbstractVerticle {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new OrderVerticle())
      .onFailure(Throwable::printStackTrace)
      .onSuccess(v -> System.out.println("Deployed OrderVerticle"));
  }

  private static final String CREATE_TABLE = """
    CREATE TABLE IF NOT EXISTS "order"
    (
        username VARCHAR PRIMARY KEY,
        value    jsonb
    )""";
  private static final String LOAD_ORDER = """
    SELECT value
    FROM "order"
    WHERE username = $1""";
  private static final String ADD_ITEM = """
    INSERT INTO "order" (username, value)
    VALUES ($1, $2)
    ON CONFLICT (username) DO UPDATE SET value = "order".value || EXCLUDED.value
    RETURNING value""";
  private static final String TRUNCATE_TABLE = "TRUNCATE TABLE \"order\"";
  private static final String DELETE_ORDER = """
    DELETE FROM "order"
    WHERE username = $1
    RETURNING value""";

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

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create(LoggerFormat.TINY))
        .handler(new XServedByHandler("order"))
        .handler(ResponseTimeHandler.create());

      router.route("/metrics").handler(PrometheusScrapingHandler.create());

      JWTAuthOptions authConfig = new JWTAuthOptions()
        .setKeyStore(new KeyStoreOptions()
          .setType("PKCS12")
          .setPath("http-proxy-playground.p12")
          .setPassword("foobar"));

      JWTAuth authProvider = JWTAuth.create(vertx, authConfig);

      router.route("/order*").handler(JWTAuthHandler.create(authProvider));

      BodyHandler bodyHandler = BodyHandler.create().setDeleteUploadedFilesOnEnd(true);

      router.get("/order").handler(this::orderList);
      router.post("/order/add")
        .handler(bodyHandler)
        .handler(this::orderAdd);
      router.post("/order/clear").handler(this::orderClear);
      router.post("/order/checkout")
        .handler(bodyHandler)
        .handler(this::orderCheckout);

      router.get("/health*").handler(HealthCheckHandler.create(vertx));

      router.route().failureHandler(ErrorHandler.create(vertx));

      KeyCertOptions keyCertOptions = new PfxOptions()
        .setPath("http-proxy-playground.p12")
        .setPassword("foobar")
        .setAlias("http-proxy-playground");

      HttpServerOptions options = new HttpServerOptions()
        .setHost(serverHost())
        .setPort(serverPort(8445))
        .setUseAlpn(false)
        .setSsl(true)
        .setKeyCertOptions(keyCertOptions)
        .setCompressionSupported(true);

      return vertx.createHttpServer(options)
        .requestHandler(router)
        .listen()
        .<Void>mapEmpty();

    }).onComplete(startPromise);
  }

  private void orderList(RoutingContext rc) {
    pool.preparedQuery(LOAD_ORDER)
      .mapping(row -> row.getJsonArray("value"))
      .execute(Tuple.of(extractUsername(rc)))
      .map(res -> computeOrderList(res.size() == 1 ? res.iterator().next() : new JsonArray()))
      .onComplete(rc::json, rc::fail);
  }

  private JsonObject computeOrderList(JsonArray items) {
    JsonObject reply = new JsonObject().put("items", items);

    reply.put("count", items.size());

    double total = 0;
    for (int i = 0; i < items.size(); i++) {
      total += items.getJsonObject(i).getDouble("price");
    }
    reply.put("total", total);

    return reply;
  }

  private void orderAdd(RoutingContext rc) {
    JsonObject item = rc.body().asJsonObject();
    if (item == null) {
      rc.fail(400);
      return;
    }

    String name = item.getString("name");
    if (name == null || name.isBlank()) {
      rc.fail(400);
      return;
    }

    String shortDescription = item.getString("shortDescription");
    if (shortDescription == null || shortDescription.isBlank()) {
      rc.fail(400);
      return;
    }

    Double price = item.getDouble("price");
    if (price == null || price <= 0) {
      rc.fail(400);
      return;
    }

    pool.preparedQuery(ADD_ITEM)
      .mapping(row -> row.getJsonArray("value"))
      .execute(Tuple.of(extractUsername(rc), new JsonArray().add(item)))
      .map(res -> computeOrderList(res.size() == 1 ? res.iterator().next() : new JsonArray()))
      .onComplete(rc::json, rc::fail);
  }

  private void orderClear(RoutingContext rc) {
    pool.query(TRUNCATE_TABLE)
      .execute()
      .map(res -> computeOrderList(new JsonArray()))
      .onComplete(rc::json, rc::fail);
  }

  private void orderCheckout(RoutingContext rc) {
    JsonObject details = rc.body().asJsonObject();
    if (details == null) {
      rc.fail(400);
      return;
    }

    String firstName = details.getString("firstName");
    if (firstName == null || firstName.isBlank()) {
      rc.fail(400);
      return;
    }

    String lastName = details.getString("lastName");
    if (lastName == null || lastName.isBlank()) {
      rc.fail(400);
      return;
    }

    pool.preparedQuery(DELETE_ORDER)
      .mapping(row -> row.getJsonArray("value"))
      .execute(Tuple.of(extractUsername(rc)))
      .compose(res -> {
        if (res.size() == 1) {
          return Future.succeededFuture(computeOrderList(res.iterator().next()));
        }
        return Future.failedFuture("No items");
      })
      .map(order -> {
        return new JsonObject()
          .put("firstName", firstName)
          .put("lastName", lastName)
          .put("order", order);
      })
      .onComplete(rc::json, rc::fail);
  }

  private static String extractUsername(RoutingContext rc) {
    return rc.user().principal().getJsonObject("sub").getString("username");
  }
}
