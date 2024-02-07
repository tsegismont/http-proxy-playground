package edge;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;

import static common.EnvUtil.DELIVERY_SERVICE;
import static common.EnvUtil.ORDER_SERVICE;

class OrderCheckoutHandler implements Handler<RoutingContext> {

  private final HttpRequest<JsonObject> orderRequest;
  private final HttpRequest<JsonObject> deliveryRequest;

  public OrderCheckoutHandler(HttpClient httpClient) {
    WebClient webClient = WebClient.wrap(httpClient);

    orderRequest = webClient.request(HttpMethod.POST, new RequestOptions().setServer(ORDER_SERVICE).setURI("/order/checkout"))
      .ssl(true)
      .expect(ResponsePredicates.STATUS_OK)
      .expect(ResponsePredicates.CONTENT_JSON)
      .as(BodyCodec.jsonObject());

    deliveryRequest = webClient.request(HttpMethod.POST, new RequestOptions().setServer(DELIVERY_SERVICE).setURI("/delivery/add"))
      .ssl(true)
      .expect(ResponsePredicates.STATUS_OK)
      .as(BodyCodec.jsonObject());
  }

  @Override
  public void handle(RoutingContext rc) {
    MultiMap params = rc.queryParams();

    JsonObject details = new JsonObject()
      .put("firstName", params.get("firstName"))
      .put("lastName", params.get("lastName"));

    String token = rc.session().get("token");

    orderRequest
      .bearerTokenAuthentication(token)
      .sendJsonObject(details)
      .compose(orderReponse -> deliveryRequest
        .bearerTokenAuthentication(token)
        .sendJsonObject(orderReponse.body()))
      .onComplete(v -> rc.redirect("/account.html"), rc::fail);
  }
}
