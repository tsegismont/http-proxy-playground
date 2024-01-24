package edge;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;

class OrderCheckoutHandler implements Handler<RoutingContext> {

  private final HttpRequest<JsonObject> orderRequest;
  private final HttpRequest<JsonObject> deliveryRequest;

  public OrderCheckoutHandler(HttpClient httpClient, JsonObject conf) {
    WebClient webClient = WebClient.wrap(httpClient);

    String orderServerHost = conf.getString("orderServerHost", "127.0.0.1");
    int orderServerPort = conf.getInteger("orderServerPort", 8082);

    orderRequest = webClient.post(orderServerPort, orderServerHost, "/order/checkout")
      .expect(ResponsePredicates.STATUS_OK)
      .expect(ResponsePredicates.CONTENT_JSON)
      .as(BodyCodec.jsonObject());

    String deliveryServerHost = conf.getString("deliveryServerHost", "127.0.0.1");
    int deliveryServerPort = conf.getInteger("deliveryServerPort", 8083);

    deliveryRequest = webClient.post(deliveryServerPort, deliveryServerHost, "/delivery/add")
      .expect(ResponsePredicates.STATUS_OK)
      .as(BodyCodec.jsonObject());
  }

  @Override
  public void handle(RoutingContext rc) {
    MultiMap params = rc.queryParams();

    JsonObject details = new JsonObject()
      .put("firstName", params.get("firstName"))
      .put("lastName", params.get("lastName"));

    orderRequest.sendJsonObject(details)
      .compose(orderReponse -> deliveryRequest.sendJsonObject(orderReponse.body()))
      .onComplete(v -> rc.redirect("/account.html"), rc::fail);
  }
}
