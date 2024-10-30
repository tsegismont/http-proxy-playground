package common;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.vertx.core.VertxOptions;
import io.vertx.launcher.application.HookContext;
import io.vertx.launcher.application.VertxApplication;
import io.vertx.launcher.application.VertxApplicationHooks;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;

public class CustomLauncher extends VertxApplication implements VertxApplicationHooks {

  public static void main(String[] args) {
    CustomLauncher app = new CustomLauncher(args);
    app.launch();
  }

  public CustomLauncher(String[] args) {
    super(args);
  }

  @Override
  public void beforeStartingVertx(HookContext context) {
    VertxOptions vertxOptions = context.vertxOptions();

    SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder().build();
    OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
      .setTracerProvider(sdkTracerProvider)
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
      .buildAndRegisterGlobal();
    vertxOptions.setTracingOptions(new OpenTelemetryOptions(openTelemetry));

    vertxOptions.setMetricsOptions(new MicrometerMetricsOptions()
      .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
      .setEnabled(true));
  }
}
