package product;

import common.XServedByHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.micrometer.PrometheusScrapingHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static common.EnvUtil.serverHost;
import static common.EnvUtil.serverPort;

public class ProductVerticle extends AbstractVerticle {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new ProductVerticle())
      .onFailure(Throwable::printStackTrace)
      .onSuccess(v -> System.out.println("Deployed ProductVerticle"));
  }

  private final Map<Integer, JsonObject> products = new HashMap<>();

  @Override
  public void start(Promise<Void> startPromise) {
    loadData();

    Router router = Router.router(vertx);

    router.route()
      .handler(LoggerHandler.create(LoggerFormat.TINY))
      .handler(new XServedByHandler("product"))
      .handler(ResponseTimeHandler.create());

    router.route("/metrics").handler(PrometheusScrapingHandler.create());

    router.get("/annoying-prefix/products").handler(this::productList);
    router.get("/annoying-prefix/product/:id")
      .handler(this::extractId)
      .handler(this::product);
    router.get("/annoying-prefix/product/:id/image")
      .handler(this::extractId)
      .handler(this::productImage);

    router.get("/static/*").handler(StaticHandler.create().setCachingEnabled(true));

    router.get("/health*").handler(HealthCheckHandler.create(vertx));

    router.route().failureHandler(ErrorHandler.create(vertx));

    HttpServerOptions options = new HttpServerOptions()
      .setHost(serverHost())
      .setPort(serverPort(8081))
      .setCompressionSupported(true);

    vertx.createHttpServer(options)
      .requestHandler(router)
      .listen()
      .<Void>mapEmpty()
      .onComplete(startPromise);
  }

  private void loadData() {
    JsonArray jsonArray = new JsonArray(vertx.fileSystem().readFileBlocking("products.json"));
    for (int i = 0; i < jsonArray.size(); i++) {
      JsonObject product = jsonArray.getJsonObject(i);
      products.put(product.getInteger("id"), product);
    }
  }

  private void productList(RoutingContext rc) {
    JsonArray reply = new JsonArray(new ArrayList<>(products.values()));
    rc.json(reply);
  }

  private void extractId(RoutingContext rc) {
    try {
      rc.put("productId", Integer.parseInt(rc.pathParam("id")));
      rc.next();
    } catch (NumberFormatException e) {
      rc.fail(400);
    }
  }

  private void product(RoutingContext rc) {
    JsonObject json = products.get(rc.<Integer>get("productId"));
    if (json == null) {
      rc.response().setStatusCode(404).end();
    } else {
      rc.json(json);
    }
  }

  private void productImage(RoutingContext rc) {
    JsonObject json = products.get(rc.<Integer>get("productId"));
    if (json == null) {
      rc.response().setStatusCode(404).end();
    } else {
      rc.reroute("/static/images/" + json.getString("image"));
    }
  }
}
