package order;

import common.XServedByHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PfxOptions;
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

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create(LoggerFormat.TINY))
        .handler(new XServedByHandler("order"))
        .handler(ResponseTimeHandler.create());

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

      String serverHost = conf.getString("serverHost", "0.0.0.0");
      Integer serverPort = conf.getInteger("serverPort", 8445);

      String serverKeyPath = conf.getString("serverKeyPath");
      String serverCertPath = conf.getString("serverCertPath");
      KeyCertOptions keyCertOptions;
      if (serverKeyPath != null && serverCertPath != null) {
        keyCertOptions = new PemKeyCertOptions().setKeyPath(serverKeyPath).setCertPath(serverCertPath);
      } else {
        keyCertOptions = new PfxOptions().setPath("http-proxy-playground.p12").setPassword("foobar").setAlias("http-proxy-playground");
      }

      HttpServerOptions options = new HttpServerOptions()
        .setHost(serverHost)
        .setPort(serverPort)
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
    rc.json(computeOrderList());
  }

  private JsonObject computeOrderList() {
    List<JsonObject> list = new ArrayList<>(items);

    JsonObject reply = new JsonObject().put("items", list);

    reply.put("count", list.size());

    double total = 0;
    for (JsonObject item : list) {
      total += item.getDouble("price");
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

    items.add(item);

    rc.json(computeOrderList());
  }

  private void orderClear(RoutingContext rc) {
    items.clear();
    rc.json(computeOrderList());
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

    JsonObject reply = new JsonObject()
      .put("firstName", firstName)
      .put("lastName", lastName)
      .put("order", computeOrderList());

    items.clear();

    rc.json(reply);
  }
}
