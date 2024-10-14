package common;

import io.vertx.core.net.Address;
import io.vertx.core.net.AddressResolver;
import io.vertx.core.net.SocketAddress;
import io.vertx.serviceresolver.ServiceAddress;
import io.vertx.serviceresolver.kube.KubeResolver;

public class EnvUtil {

  private static final boolean IS_KUBE = isKube();

  public static final AddressResolver RESOLVER = resolver();

  public static Address PRODUCT_SERVICE = productService();
  public static Address ORDER_SERVICE = orderService();
  public static Address DELIVERY_SERVICE = deliveryService();

  public static String serverHost() {
    return getOrDefaultString("SERVER_HOST", "0.0.0.0");
  }

  public static int serverPort(int defaultPort) {
    return getOrDefaultInt("SERVER_PORT", defaultPort);
  }

  public static String postgresServerHost() {
    return getOrDefaultString("POSTGRES_SERVER_HOST", "localhost");
  }

  public static int postgresServerPort() {
    return getOrDefaultInt("POSTGRES_SERVER_PORT", 5432);
  }

  public static String postgresServerDatabase() {
    return getOrDefaultString("POSTGRES_SERVER_DATABASE", "postgres");
  }

  public static String postgresServerUser() {
    return getOrDefaultString("POSTGRES_SERVER_USER", "postgres");
  }

  public static String postgresServerPassword() {
    return getOrDefaultString("POSTGRES_SERVER_PASSWORD", "mysecretpassword");
  }

  private static boolean isKube() {
    return System.getenv().containsKey("KUBERNETES_SERVICE_HOST");
  }

  private static AddressResolver resolver() {
    return IS_KUBE ? KubeResolver.create() : null;
  }

  private static Address productService() {
    return IS_KUBE ? ServiceAddress.of("product-service") : SocketAddress.inetSocketAddress(productServerPort(), productServerHost());
  }

  private static String productServerHost() {
    return getOrDefaultString("PRODUCT_SERVER_HOST", "localhost");
  }

  private static int productServerPort() {
    return getOrDefaultInt("PRODUCT_SERVER_PORT", 8081);
  }

  private static Address orderService() {
    return IS_KUBE ? ServiceAddress.of("order-service") : SocketAddress.inetSocketAddress(orderServerPort(), orderServerHost());
  }

  private static String orderServerHost() {
    return getOrDefaultString("ORDER_SERVER_HOST", "localhost");
  }

  private static int orderServerPort() {
    return getOrDefaultInt("ORDER_SERVER_PORT", 8445);
  }

  private static Address deliveryService() {
    return IS_KUBE ? ServiceAddress.of("delivery-service") : SocketAddress.inetSocketAddress(deliveryServerPort(), deliveryServerHost());
  }

  private static String deliveryServerHost() {
    return getOrDefaultString("DELIVERY_SERVER_HOST", "localhost");
  }

  private static int deliveryServerPort() {
    return getOrDefaultInt("DELIVERY_SERVER_PORT", 8446);
  }

  private static String getOrDefaultString(String name, String defaultValue) {
    return System.getenv().getOrDefault(name, defaultValue);
  }

  private static int getOrDefaultInt(String name, int defaultPort) {
    String serverPort = System.getenv().get(name);
    try {
      return serverPort != null ? Integer.parseInt(serverPort) : defaultPort;
    } catch (NumberFormatException ignore) {
      return defaultPort;
    }
  }

  private EnvUtil() {
  }
}
