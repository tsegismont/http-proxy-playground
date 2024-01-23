package common;

import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;

public class HostnameHandler implements PlatformHandler {

  private static final CharSequence X_SERVED_BY = HttpHeaders.createOptimized("x-served-by");
  private static final CharSequence HOSTNAME = HttpHeaders.createOptimized(System.getenv().getOrDefault("HOSTNAME", "<unknown>"));

  @Override
  public void handle(RoutingContext rc) {
    rc.addHeadersEndHandler(v -> rc.response().putHeader(X_SERVED_BY, HOSTNAME));
    rc.next();
  }
}
