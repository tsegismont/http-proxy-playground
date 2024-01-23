package common;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;

public class HostnameHandler implements PlatformHandler {

  private static final CharSequence X_SERVED_BY = HttpHeaders.createOptimized("x-served-by");

  private final CharSequence headerValue;

  public HostnameHandler(String serviceName) {
    String hostname = System.getenv().getOrDefault("HOSTNAME", "<unknown>");
    headerValue = HttpHeaders.createOptimized(serviceName + "-" + hostname);
  }

  @Override
  public void handle(RoutingContext rc) {
    rc.addHeadersEndHandler(v -> rc.response().headers().add(X_SERVED_BY, headerValue));
    rc.next();
  }
}
