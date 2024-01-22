package product;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.PlatformHandler;
import io.vertx.ext.web.handler.ResponseTimeHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ProductVerticle extends AbstractVerticle {

  private final Map<Integer, JsonObject> products = new HashMap<>();

  @Override
  public void start(Promise<Void> startPromise) {
    loadData();

    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(conf -> {

      String serverHost = conf.getString("serverHost", "0.0.0.0");
      Integer serverPort = conf.getInteger("serverPort", 8081);

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create())
        .handler((PlatformHandler) this::hostname)
        .handler(ResponseTimeHandler.create());

      router.get("/products").handler(this::productList);
      router.get("/product/:id")
        .handler(this::extractId)
        .handler(this::product);
      router.get("/product/:id/image")
        .handler(this::extractId)
        .handler(this::productImage);

      router.get("/health*").handler(HealthCheckHandler.create(vertx));

      router.route().failureHandler(ErrorHandler.create(vertx));

      return vertx.createHttpServer(new HttpServerOptions().setHost(serverHost).setPort(serverPort))
        .requestHandler(router)
        .listen()
        .<Void>mapEmpty();

    }).onComplete(startPromise);
  }

  private void hostname(RoutingContext rc) {
    rc.addHeadersEndHandler(v -> {
      rc.response().putHeader("x-served-by", System.getenv().getOrDefault("HOSTNAME", "<unknown>"));
    });
    rc.next();

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
      rc.response().sendFile("images/" + json.getString("image"));
    }
  }
}
