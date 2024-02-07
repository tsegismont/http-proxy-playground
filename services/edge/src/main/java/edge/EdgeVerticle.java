package edge;

import common.EnvUtil;
import common.XServedByHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.loadbalancing.LoadBalancer;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.auth.properties.PropertyFileAuthentication;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.*;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.proxy.handler.ProxyHandler;
import io.vertx.ext.web.sstore.cookie.CookieSessionStore;
import io.vertx.httpproxy.*;
import io.vertx.httpproxy.cache.CacheOptions;
import io.vertx.micrometer.PrometheusScrapingHandler;

import java.util.Set;

import static common.EnvUtil.*;
import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;
import static io.vertx.core.http.HttpHeaders.COOKIE;

public class EdgeVerticle extends AbstractVerticle {

  private JsonObject identities;

  @Override
  public void start(Promise<Void> startPromise) {
    identities = vertx.fileSystem().readFileBlocking("identities.json").toJsonObject();

    HttpClientOptions clientOptions = new HttpClientOptions()
      .setTrustAll(true)
      .setVerifyHost(false)
      .setDecompressionSupported(true);

    HttpClient httpClient = vertx.httpClientBuilder()
      .with(clientOptions)
      .withAddressResolver(RESOLVER)
      .withLoadBalancer(LoadBalancer.ROUND_ROBIN)
      .build();

    Router router = Router.router(vertx);

    router.route()
      .handler(LoggerHandler.create(LoggerFormat.TINY))
      .handler(new XServedByHandler("edge"))
      .handler(ResponseTimeHandler.create());

    router.route("/metrics").handler(PrometheusScrapingHandler.create());

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
      .handler(new OrderCheckoutHandler(httpClient));

    ProxyHandler productProxyHandler = productProxyHandler(httpClient);
    router.route("/product*")
      .handler(basicAuthHandler)
      .handler(productProxyHandler);

    ProxyHandler orderProxyHandler = orderProxyHandler(httpClient);
    router.route("/order*")
      .handler(basicAuthHandler)
      .handler(tokenMaintenanceHandler)
      .handler(orderProxyHandler);

    ProxyHandler deliveryProxyHandler = deliveryProxyHandler(httpClient);
    router.route("/delivery*")
      .handler(basicAuthHandler)
      .handler(tokenMaintenanceHandler)
      .handler(deliveryProxyHandler);

    router.get("/health*").handler(HealthCheckHandler.create(vertx));

    router.route().failureHandler(ErrorHandler.create(vertx));

    KeyCertOptions keyCertOptions = new PfxOptions()
      .setPath("http-proxy-playground.p12")
      .setPassword("foobar")
      .setAlias("http-proxy-playground");

    HttpServerOptions options = new HttpServerOptions()
      .setHost(serverHost())
      .setPort(serverPort(8443))
      .setUseAlpn(true)
      .setSsl(true)
      .setKeyCertOptions(keyCertOptions)
      .setCompressionSupported(true)
      .setTracingPolicy(TracingPolicy.ALWAYS);

    vertx.createHttpServer(options)
      .requestHandler(router)
      .listen()
      .<Void>mapEmpty()
      .onComplete(startPromise);
  }

  private void identity(RoutingContext rc) {
    String username = rc.user().get().principal().getString("username");
    JsonObject reply = identities.getJsonObject(username);
    rc.json(reply);
  }

  private ProxyHandler productProxyHandler(HttpClient httpClient) {
    CacheOptions cacheOptions = new CacheOptions()
      .setMaxSize(512);

    ProxyOptions proxyOptions = new ProxyOptions()
      .setCacheOptions(cacheOptions)
      .setSupportWebSocket(false);

    HttpProxy httpProxy = HttpProxy.reverseProxy(proxyOptions, httpClient);
    httpProxy.originRequestProvider((req, client) -> {
      RequestOptions requestOptions = new RequestOptions()
        .setServer(PRODUCT_SERVICE);
      return client.request(requestOptions);
    });

    httpProxy.addInterceptor(new HeadersInterceptor(Set.of(COOKIE, AUTHORIZATION), Set.of()));
    httpProxy.addInterceptor(new ProductPathInterceptor());
    httpProxy.addInterceptor(new ProductImageFieldInterceptor(vertx));

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler orderProxyHandler(HttpClient httpClient) {
    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(false), httpClient);
    httpProxy.originRequestProvider((request, client) -> {
      RequestOptions requestOptions = new RequestOptions()
        .setServer(ORDER_SERVICE)
        .setSsl(true);
      return client.request(requestOptions);
    });
    httpProxy.addInterceptor(new HeadersInterceptor(Set.of(COOKIE, AUTHORIZATION), Set.of()));
    httpProxy.addInterceptor(new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        String token = Vertx.currentContext().getLocal("token");
        context.request().putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return context.sendRequest();
      }
    });

    return ProxyHandler.create(httpProxy);
  }

  private ProxyHandler deliveryProxyHandler(HttpClient httpClient) {
    HttpProxy httpProxy = HttpProxy.reverseProxy(new ProxyOptions().setSupportWebSocket(true), httpClient);
    httpProxy.originRequestProvider((request, client) -> {
      String token = Vertx.currentContext().getLocal("token");
      RequestOptions requestOptions = new RequestOptions()
        .setServer(EnvUtil.DELIVERY_SERVICE)
        .setSsl(true)
        .putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      return client.request(requestOptions);
    });
    httpProxy.addInterceptor(new HeadersInterceptor(Set.of(COOKIE), Set.of()));

    return ProxyHandler.create(httpProxy);
  }
}
