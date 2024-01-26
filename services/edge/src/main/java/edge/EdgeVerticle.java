package edge;

import common.XServedByHandler;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.auth.properties.PropertyFileAuthentication;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.proxy.handler.ProxyHandler;
import io.vertx.ext.web.sstore.cookie.CookieSessionStore;
import io.vertx.httpproxy.*;
import io.vertx.httpproxy.cache.CacheOptions;

public class EdgeVerticle extends AbstractVerticle {

  private JsonObject identities;

  @Override
  public void start(Promise<Void> startPromise) {
    identities = vertx.fileSystem().readFileBlocking("identities.json").toJsonObject();

    HttpClientOptions clientOptions = new HttpClientOptions()
      .setTrustAll(true)
      .setVerifyHost(false)
      .setDecompressionSupported(true);

    HttpClient httpClient = vertx.createHttpClient(clientOptions);

    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(conf -> {

      Router router = Router.router(vertx);

      router.route()
        .handler(LoggerHandler.create(LoggerFormat.TINY))
        .handler(new XServedByHandler("edge"))
        .handler(ResponseTimeHandler.create());

      router.get().handler(StaticHandler.create().setCachingEnabled(true));

      router.route().handler(SessionHandler.create(CookieSessionStore.create(vertx, "foobar")));

      BasicAuthHandler basicAuthHandler = BasicAuthHandler.create(PropertyFileAuthentication.create(vertx, "users.properties"));
      TokenMaintenanceHandler tokenMaintenanceHandler = new TokenMaintenanceHandler(vertx);

      router.get("/identity")
        .handler(basicAuthHandler)
        .handler(tokenMaintenanceHandler)
        .handler(this::identity);

      router.get("/order/checkout")
        .handler(BodyHandler.create().setDeleteUploadedFilesOnEnd(true))
        .handler(basicAuthHandler)
        .handler(tokenMaintenanceHandler)
        .handler(new OrderCheckoutHandler(httpClient, conf));

      ProxyHandler productProxyHandler = productProxyHandler(conf, httpClient);
      router.route("/product*")
        .handler(basicAuthHandler)
        .handler(productProxyHandler);

      ProxyHandler orderProxyHandler = orderProxyHandler(conf, httpClient);
      router.route("/order*")
        .handler(basicAuthHandler)
        .handler(tokenMaintenanceHandler)
        .handler(orderProxyHandler);

      ProxyHandler deliveryProxyHandler = deliveryProxyHandler(conf, httpClient);
      router.route("/delivery*")
        .handler(basicAuthHandler)
        .handler(tokenMaintenanceHandler)
        .handler(deliveryProxyHandler);

      router.get("/health*").handler(HealthCheckHandler.create(vertx));

      router.route().failureHandler(ErrorHandler.create(vertx));

      String serverHost = conf.getString("serverHost", "0.0.0.0");
      Integer serverPort = conf.getInteger("serverPort", 8443);
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

  private void identity(RoutingContext rc) {
    String username = rc.user().principal().getString("username");
    JsonObject reply = identities.getJsonObject(username);
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
    httpProxy.addInterceptor(new ProductPathInterceptor());
    httpProxy.addInterceptor(new ProductImageFieldInterceptor(vertx));
    httpProxy.addInterceptor(new XServedByHeaderInterceptor());

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler orderProxyHandler(JsonObject conf, HttpClient httpClient) {
    String orderServerHost = conf.getString("orderServerHost", "127.0.0.1");
    Integer orderServerPort = conf.getInteger("orderServerPort", 8445);
    SocketAddress origin = SocketAddress.inetSocketAddress(orderServerPort, orderServerHost);

    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(false), httpClient);
    httpProxy.originRequestProvider((request, client) -> {
      RequestOptions requestOptions = new RequestOptions()
        .setServer(origin)
        .setSsl(true);
      return client.request(requestOptions);
    });
    httpProxy.addInterceptor(new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        String token = Vertx.currentContext().getLocal("token");
        context.request().putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return context.sendRequest();
      }
    });
    httpProxy.addInterceptor(new XServedByHeaderInterceptor());

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler deliveryProxyHandler(JsonObject conf, HttpClient httpClient) {
    String deliveryServerHost = conf.getString("deliveryServerHost", "127.0.0.1");
    Integer deliveryServerPort = conf.getInteger("deliveryServerPort", 8446);
    SocketAddress origin = SocketAddress.inetSocketAddress(deliveryServerPort, deliveryServerHost);

    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(true), httpClient);
    httpProxy.originRequestProvider((request, client) -> {
      String token = Vertx.currentContext().getLocal("token");
      RequestOptions requestOptions = new RequestOptions()
        .setServer(origin)
        .setSsl(true)
        .putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      return client.request(requestOptions);
    });
    httpProxy.addInterceptor(new XServedByHeaderInterceptor());

    return ProxyHandler.create(httpProxy);
  }
}
