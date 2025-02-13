package io.jooby.i2452;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.NotNull;

import io.jooby.exception.StatusCodeException;
import io.jooby.junit.ServerTest;
import io.jooby.junit.ServerTestRunner;
import io.jooby.Context;
import io.jooby.ErrorHandler;
import io.jooby.StatusCode;
import io.jooby.test.WebClient;

public class Issue2452 {
  @ServerTest
  public void shouldHandleCompletableFutureError(ServerTestRunner runner) {
    runner.define(app -> {
      app.get("/2452", ctx -> CompletableFuture.supplyAsync(
          () -> {
            throw new StatusCodeException(StatusCode.NOT_FOUND);
          }
      ));

      app.error((ctx, cause, code) -> {
        ctx.setResponseCode(code);
        ctx.send(cause.getClass().getSimpleName());
      });
    }).ready((WebClient http) -> {

      http
          .get("/2452", rsp -> {
            assertEquals("StatusCodeException",
                rsp.body().string());
            assertEquals(StatusCode.NOT_FOUND.value(),
                rsp.code());
          });
    });
  }
}
