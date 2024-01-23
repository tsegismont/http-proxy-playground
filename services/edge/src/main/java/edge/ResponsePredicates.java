package edge;

import io.vertx.ext.web.client.predicate.ResponsePredicate;
import io.vertx.ext.web.handler.HttpException;

import static io.vertx.ext.web.client.predicate.ResponsePredicate.*;

public class ResponsePredicates {

  public static final ResponsePredicate STATUS_OK = create(SC_OK, res -> {
    return new HttpException(res.response().statusCode(), res.message());
  });

  public static final ResponsePredicate CONTENT_JSON = create(JSON);

  private ResponsePredicates() {
    // Constants class
  }
}
