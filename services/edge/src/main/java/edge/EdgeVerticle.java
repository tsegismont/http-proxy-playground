package edge;

import common.XServedByHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.proxy.handler.ProxyHandler;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyOptions;
import io.vertx.httpproxy.cache.CacheOptions;

public class EdgeVerticle extends AbstractVerticle {

  private WebClient webClient;

  @Override
  public void start(Promise<Void> startPromise) {
    HttpClient httpClient = vertx.createHttpClient();
    webClient = WebClient.wrap(httpClient);

    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(conf -> {

      String serverHost = conf.getString("serverHost", "0.0.0.0");
      Integer serverPort = conf.getInteger("serverPort", 8080);

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create(LoggerFormat.TINY))
        .handler(new XServedByHandler("edge"))
        .handler(ResponseTimeHandler.create());

      router.get().handler(StaticHandler.create().setCachingEnabled(true));

      router.get("/identity").handler(this::identity);

      router.get("/order/checkout")
        .handler(BodyHandler.create().setDeleteUploadedFilesOnEnd(true))
        .handler(new OrderCheckoutHandler(httpClient, conf));

      ProxyHandler productProxyHandler = productProxyHandler(conf, httpClient);
      router.route("/product*").handler(productProxyHandler);

      ProxyHandler orderProxyHandler = orderProxyHandler(conf, httpClient);
      router.route("/order*").handler(orderProxyHandler);

      ProxyHandler deliveryProxyHandler = deliveryProxyHandler(conf, httpClient);
      router.route("/delivery*").handler(deliveryProxyHandler);

      router.get("/health*").handler(HealthCheckHandler.create(vertx));

      router.route().failureHandler(ErrorHandler.create(vertx));

      return vertx.createHttpServer(new HttpServerOptions().setHost(serverHost).setPort(serverPort))
        .requestHandler(router)
        .listen()
        .<Void>mapEmpty();

    }).onComplete(startPromise);
  }

  private void identity(RoutingContext rc) {
    JsonObject reply = new JsonObject()
      .put("firstName", "Thomas")
      .put("lastName", "Segismont");
    rc.json(reply);
  }

  private ProxyHandler productProxyHandler(JsonObject conf, HttpClient httpClient) {
    String productServerHost = conf.getString("productServerHost", "127.0.0.1");
    Integer productServerPort = conf.getInteger("productServerPort", 8081);

    CacheOptions cacheOptions = new CacheOptions()
      .setMaxSize(512);

    ProxyOptions proxyOptions = new ProxyOptions()
      .setCacheOptions(cacheOptions)
      .setSupportWebSocket(false);

    HttpProxy httpProxy = HttpProxy.reverseProxy(proxyOptions, httpClient);
    httpProxy.origin(productServerPort, productServerHost);
    httpProxy.addInterceptor(new XServedByHeaderInterceptor());

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler orderProxyHandler(JsonObject conf, HttpClient httpClient) {
    String orderServerHost = conf.getString("orderServerHost", "127.0.0.1");
    Integer orderServerPort = conf.getInteger("orderServerPort", 8082);

    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(false), httpClient);
    httpProxy.origin(orderServerPort, orderServerHost);
    httpProxy.addInterceptor(new XServedByHeaderInterceptor());

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler deliveryProxyHandler(JsonObject conf, HttpClient httpClient) {
    String deliveryServerHost = conf.getString("deliveryServerHost", "127.0.0.1");
    Integer deliveryServerPort = conf.getInteger("deliveryServerPort", 8083);

    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(true), httpClient);
    httpProxy.origin(deliveryServerPort, deliveryServerHost);
    httpProxy.addInterceptor(new XServedByHeaderInterceptor());

    return ProxyHandler.create(httpProxy);
  }
}
