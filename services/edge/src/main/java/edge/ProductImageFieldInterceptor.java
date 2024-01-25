package edge;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.httpproxy.Body;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;

class ProductImageFieldInterceptor implements ProxyInterceptor {

  private final Vertx vertx;

  public ProductImageFieldInterceptor(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public Future<Void> handleProxyResponse(ProxyContext context) {
    String uri = context.request().getURI();
    if (!uri.endsWith("/products")) {
      return context.sendResponse();
    }
    ProxyResponse proxyResponse = context.response();
    Body body = proxyResponse.getBody();

    return vertx.fileSystem().createTempFile("foo", "bar")
      .compose(tempFileName -> {
        return vertx.fileSystem().open(tempFileName, new OpenOptions())
          .compose(asyncFile -> {
            return body.stream().pipeTo(asyncFile).compose(v -> vertx.fileSystem().readFile(tempFileName).map(Buffer::toJsonArray));
          }).onComplete(v -> vertx.fileSystem().delete(tempFileName));
      })
      .map(jsonArray -> {
        for (int i = 0; i < jsonArray.size(); i++) {
          JsonObject jsonObject = jsonArray.getJsonObject(i);
          jsonObject.remove("image");
        }
        return jsonArray;
      })
      .compose(jsonArray -> {
        proxyResponse.setBody(Body.body(jsonArray.toBuffer()));
        return context.sendResponse();
      });
  }
}
