package common;

public class EnvUtil {

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

  public static String productServerHost() {
    return getOrDefaultString("PRODUCT_SERVER_HOST", "localhost");
  }

  public static int productServerPort() {
    return getOrDefaultInt("PRODUCT_SERVER_PORT", 8081);
  }

  public static String orderServerHost() {
    return getOrDefaultString("ORDER_SERVER_HOST", "localhost");
  }

  public static int orderServerPort() {
    return getOrDefaultInt("ORDER_SERVER_PORT", 8445);
  }

  public static String deliveryServerHost() {
    return getOrDefaultString("DELIVERY_SERVER_HOST", "localhost");
  }

  public static int deliveryServerPort() {
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
