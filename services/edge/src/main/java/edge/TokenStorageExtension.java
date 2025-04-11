package edge;

import io.vertx.core.internal.VertxBootstrap;
import io.vertx.core.spi.VertxServiceProvider;
import io.vertx.core.spi.context.storage.ContextLocal;

public class TokenStorageExtension implements VertxServiceProvider {

  final static ContextLocal<String> TOKEN_KEY = ContextLocal.registerLocal(String.class);

  @Override
  public void init(VertxBootstrap builder) {
  }
}
