package edge;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.KeyStoreOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;

import static edge.TokenStorageExtension.TOKEN_KEY;

class TokenMaintenanceHandler implements Handler<RoutingContext> {

  private final JWTAuth authProvider;

  TokenMaintenanceHandler(Vertx vertx) {
    JWTAuthOptions authConfig = new JWTAuthOptions()
      .setKeyStore(new KeyStoreOptions()
        .setType("PKCS12")
        .setPath("http-proxy-playground.p12")
        .setPassword("foobar"));

    authProvider = JWTAuth.create(vertx, authConfig);
  }

  @Override
  public void handle(RoutingContext rc) {
    Session session = rc.session();
    String token;
    if ((token = session.get("token")) == null) {
      JsonObject claims = new JsonObject().put("sub", rc.user().principal());
      token = authProvider.generateToken(claims, new JWTOptions().setIgnoreExpiration(true));
      session.put("token", token);
    }
    rc.vertx().getOrCreateContext().putLocal(TOKEN_KEY, token);
    rc.next();
  }
}
