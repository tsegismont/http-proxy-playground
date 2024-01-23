package edge;

import common.HostnameHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.ResponseTimeHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.proxy.handler.ProxyHandler;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyOptions;

public class EdgeVerticle extends AbstractVerticle {

  @Override
  public void start(Promise<Void> startPromise) {
    HttpClient httpClient = vertx.createHttpClient();

    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(conf -> {

      String serverHost = conf.getString("serverHost", "0.0.0.0");
      Integer serverPort = conf.getInteger("serverPort", 8080);

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create())
        .handler(new HostnameHandler("edge"))
        .handler(ResponseTimeHandler.create());

      router.get().handler(StaticHandler.create());

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

  private ProxyHandler productProxyHandler(JsonObject conf, HttpClient httpClient) {
    String productServerHost = conf.getString("productServerHost", "127.0.0.1");
    Integer productServerPort = conf.getInteger("productServerPort", 8081);

    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(false), httpClient);
    httpProxy.origin(productServerPort, productServerHost);

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler orderProxyHandler(JsonObject conf, HttpClient httpClient) {
    String orderServerHost = conf.getString("orderServerHost", "127.0.0.1");
    Integer orderServerPort = conf.getInteger("orderServerPort", 8082);

    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(false), httpClient);
    httpProxy.origin(orderServerPort, orderServerHost);

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler deliveryProxyHandler(JsonObject conf, HttpClient httpClient) {
    String deliveryServerHost = conf.getString("deliveryServerHost", "127.0.0.1");
    Integer deliveryServerPort = conf.getInteger("deliveryServerPort", 8083);

    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(true), httpClient);
    httpProxy.origin(deliveryServerPort, deliveryServerHost);

    return ProxyHandler.create(httpProxy);
  }
}
