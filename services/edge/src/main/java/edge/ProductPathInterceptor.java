package edge;

import io.vertx.core.Future;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

class ProductPathInterceptor implements ProxyInterceptor {
  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    ProxyRequest proxyRequest = context.request();
    proxyRequest.setURI("/annoying-prefix" + proxyRequest.getURI());
    return context.sendRequest();
  }
}
