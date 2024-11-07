package edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;

import java.util.Objects;
import java.util.Set;

class HeadersInterceptor implements ProxyInterceptor {

  private final Set<CharSequence> requestHeaders;
  private final Set<CharSequence> responseHeaders;

  public HeadersInterceptor(Set<CharSequence> requestHeaders, Set<CharSequence> responseHeaders) {
    this.requestHeaders = Objects.requireNonNull(requestHeaders);
    this.responseHeaders = Objects.requireNonNull(responseHeaders);
  }

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    ProxyRequest proxyRequest = context.request();
    filterRequest(proxyRequest.headers());
    return context.sendRequest();
  }

  private void filterRequest(MultiMap headers) {
    requestHeaders.forEach(headers::remove);
  }

  @Override
  public Future<Void> handleProxyResponse(ProxyContext context) {
    ProxyResponse proxyResponse = context.response();
    filterResponse(proxyResponse.headers());
    return context.sendResponse();
  }

  private void filterResponse(MultiMap headers) {
    responseHeaders.forEach(headers::remove);
  }

  @Override
  public boolean allowApplyToWebSocket() {
    return true;
  }
}
