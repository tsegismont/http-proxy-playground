package edge;

import io.vertx.core.Expectation;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.ext.web.handler.HttpException;

import static io.vertx.core.http.HttpResponseExpectation.JSON;
import static io.vertx.core.http.HttpResponseExpectation.SC_OK;

public class ResponseExpectations {

  public static final Expectation<HttpResponseHead> STATUS_OK = SC_OK.wrappingFailure((httpResponseHead, throwable) -> {
    return new HttpException(httpResponseHead.statusCode(), throwable.getMessage());
  });

  public static final Expectation<HttpResponseHead> CONTENT_JSON = JSON;

  private ResponseExpectations() {
    // Constants class
  }
}
