package order;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;

import java.util.ArrayList;
import java.util.List;

public class OrderVerticle extends AbstractVerticle {

  private final List<JsonObject> items = new ArrayList<>();

  @Override
  public void start(Promise<Void> startPromise) {
    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(conf -> {

      String serverHost = conf.getString("serverHost", "0.0.0.0");
      Integer serverPort = conf.getInteger("serverPort", 8082);

      Router router = Router.router(vertx);

      router.route().handler(LoggerHandler.create()).handler((PlatformHandler) this::hostname).handler(ResponseTimeHandler.create());

      router.get("/order").handler(this::orderList);
      router.post("/order/add").handler(BodyHandler.create().setDeleteUploadedFilesOnEnd(true)).handler(this::orderAdd);
      router.post("/order/clear").handler(this::orderClear);

      router.get("/health*").handler(HealthCheckHandler.create(vertx));

      router.route().failureHandler(ErrorHandler.create(vertx));

      return vertx.createHttpServer(new HttpServerOptions().setHost(serverHost).setPort(serverPort)).requestHandler(router).listen().<Void>mapEmpty();

    }).onComplete(startPromise);
  }

  private void hostname(RoutingContext rc) {
    rc.addHeadersEndHandler(v -> {
      rc.response().putHeader("x-served-by", System.getenv().getOrDefault("HOSTNAME", "<unknown>"));
    });
    rc.next();

  }

  private void orderList(RoutingContext rc) {
    rc.json(computeOrderList());
  }

  private JsonObject computeOrderList() {
    JsonObject reply = new JsonObject().put("items", items);

    reply.put("count", items.size());

    double total = 0;
    for (JsonObject item : items) {
      total += item.getDouble("price");
    }
    reply.put("total", total);

    return reply;
  }

  private void orderAdd(RoutingContext rc) {
    JsonObject item = rc.body().asJsonObject();

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

    items.add(item);

    rc.json(computeOrderList());
  }

  private void orderClear(RoutingContext rc) {
    items.clear();
    rc.json(computeOrderList());
  }
}
