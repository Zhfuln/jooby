/**
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.jetty;

import com.typesafe.config.Config;
import io.jooby.Jooby;
import io.jooby.ServerOptions;
import io.jooby.SneakyThrows;
import io.jooby.SslOptions;
import io.jooby.WebSocket;
import io.jooby.internal.jetty.JettyHandler;
import io.jooby.internal.jetty.JettyWebSocket;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.MultiPartFormDataCompliance;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

import javax.annotation.Nonnull;
import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Web server implementation using <a href="https://www.eclipse.org/jetty/">Jetty</a>.
 *
 * @author edgar
 * @since 2.0.0
 */
public class Jetty extends io.jooby.Server.Base {

  private static final int THREADS = 200;

  private Server server;

  private List<Jooby> applications = new ArrayList<>();

  private ServerOptions options = new ServerOptions()
      .setServer("jetty")
      .setWorkerThreads(THREADS);

  static {
    System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.Slf4jLog");
  }

  @Nonnull @Override public Jetty setOptions(@Nonnull ServerOptions options) {
    this.options = options
        .setWorkerThreads(options.getWorkerThreads(THREADS));
    return this;
  }

  @Nonnull @Override public ServerOptions getOptions() {
    return options;
  }

  @Nonnull @Override public io.jooby.Server start(Jooby application) {
    try {
      System.setProperty("org.eclipse.jetty.util.UrlEncoded.charset", "utf-8");
      /** Set max request size attribute: */
      System.setProperty("org.eclipse.jetty.server.Request.maxFormContentSize",
          Long.toString(options.getMaxRequestSize()));

      applications.add(application);

      addShutdownHook();

      QueuedThreadPool executor = new QueuedThreadPool(options.getWorkerThreads());
      executor.setName("worker");

      fireStart(applications, executor);

      this.server = new Server(executor);
      server.setStopAtShutdown(false);

      HttpConfiguration httpConf = new HttpConfiguration();
      httpConf.setOutputBufferSize(options.getBufferSize());
      httpConf.setOutputAggregationSize(options.getBufferSize());
      httpConf.setSendXPoweredBy(false);
      httpConf.setSendDateHeader(options.getDefaultHeaders());
      httpConf.setSendServerVersion(false);
      httpConf.setMultiPartFormDataCompliance(MultiPartFormDataCompliance.RFC7578);
      ServerConnector http = new ServerConnector(server);
      http.addConnectionFactory(new HttpConnectionFactory(httpConf));
      http.setPort(options.getPort());
      http.setHost(options.getHost());

      server.addConnector(http);

      if (options.isSSLEnabled()) {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory
            .setSslContext(options.getSSLContext(application.getEnvironment().getClassLoader()));

        SslOptions.ClientAuth clientAuth = Optional.ofNullable(options.getSsl())
            .map(SslOptions::getClientAuth)
            .orElse(SslOptions.ClientAuth.NONE);
        if (clientAuth == SslOptions.ClientAuth.REQUESTED) {
          sslContextFactory.setWantClientAuth(true);
        } else if (clientAuth == SslOptions.ClientAuth.REQUIRED) {
          sslContextFactory.setNeedClientAuth(true);
        }

        HttpConfiguration httpsConf = new HttpConfiguration(httpConf);
        httpsConf.addCustomizer(new SecureRequestCustomizer());

        ServerConnector https = new ServerConnector(server, sslContextFactory);
        https.addConnectionFactory(new HttpConnectionFactory(httpsConf));
        https.setPort(options.getSecurePort());
        https.setHost(options.getHost());

        server.addConnector(https);
      }

      ContextHandler context = new ContextHandler();

      AbstractHandler handler = new JettyHandler(applications.get(0), options.getBufferSize(),
          options.getMaxRequestSize(), options.getDefaultHeaders());

      if (options.getCompressionLevel() != null) {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setCompressionLevel(options.getCompressionLevel());
        gzipHandler.setHandler(handler);
        context.setHandler(gzipHandler);
      } else {
        context.setHandler(handler);
      }
      /* ********************************* WebSocket *************************************/
      context.setAttribute(DecoratedObjectFactory.ATTR, new DecoratedObjectFactory());
      WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
      policy.setMaxTextMessageBufferSize(WebSocket.MAX_BUFFER_SIZE);
      policy.setMaxTextMessageSize(WebSocket.MAX_BUFFER_SIZE);
      Config conf = application.getConfig();
      long timeout = conf.hasPath("websocket.idleTimeout")
          ? conf.getDuration("websocket.idleTimeout", TimeUnit.MINUTES)
          : 5;
      policy.setIdleTimeout(TimeUnit.MINUTES.toMillis(timeout));
      WebSocketServerFactory wssf = new WebSocketServerFactory(context.getServletContext(), policy);
      context.setAttribute(JettyWebSocket.WEBSOCKET_SERVER_FACTORY, wssf);
      context.addManaged(wssf);

      server.setHandler(context);

      server.start();

      fireReady(applications);
    } catch (Exception x) {
      if (io.jooby.Server.isAddressInUse(x.getCause())) {
        x = new BindException("Address already in use: " + options.getPort());
      }
      throw SneakyThrows.propagate(x);
    }

    return this;
  }

  @Nonnull @Override public synchronized io.jooby.Server stop() {
    fireStop(applications);
    if (server != null) {
      try {
        server.stop();
      } catch (Exception x) {
        throw SneakyThrows.propagate(x);
      } finally {
        server = null;
      }
    }
    return this;
  }
}
