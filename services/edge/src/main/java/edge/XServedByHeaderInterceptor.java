package edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;

import static common.XServedByHandler.X_SERVED_BY;

class XServedByHeaderInterceptor implements ProxyInterceptor {

  @Override
  public Future<Void> handleProxyResponse(ProxyContext context) {
    ProxyResponse proxyResponse = context.response();
    filter(proxyResponse.headers());
    return context.sendResponse();
  }

  private void filter(MultiMap headers) {
    headers.remove(X_SERVED_BY);
  }
}
