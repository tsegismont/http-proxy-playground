package edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

import static common.XServedByHandler.X_SERVED_BY;
import static io.vertx.core.http.HttpHeaders.AUTHORIZATION;
import static io.vertx.core.http.HttpHeaders.COOKIE;

class HeadersInterceptor implements ProxyInterceptor {

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    ProxyRequest proxyRequest = context.request();
    filterRequest(proxyRequest.headers());
    return context.sendRequest();
  }

  private void filterRequest(MultiMap headers) {
    headers
      .remove(COOKIE)
      .remove(AUTHORIZATION);
  }

  @Override
  public Future<Void> handleProxyResponse(ProxyContext context) {
    ProxyResponse proxyResponse = context.response();
    filterResponse(proxyResponse.headers());
    return context.sendResponse();
  }

  private void filterResponse(MultiMap headers) {
    headers.remove(X_SERVED_BY);
  }
}
