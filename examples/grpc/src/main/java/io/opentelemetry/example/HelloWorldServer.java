/*
 * Copyright 2015 The gRPC Authors
 * Copyright 2019, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.example;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.OpenTelemetry;
import io.opentelemetry.context.ContextUtils;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporters.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.TracerSdkProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.Status;
import io.opentelemetry.trace.Tracer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.Logger;

/** Server that manages startup/shutdown of a {@code Greeter} server. */
public class HelloWorldServer {
  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());
  private Server server;
  private final int port = 50051;

  // OTel API
  Tracer tracer = OpenTelemetry.getTracer("io.opentelemetry.example.HelloWorldServer");
  // Export traces as log
  LoggingSpanExporter exporter = new LoggingSpanExporter();
  // Share context via text
  TextMapPropagator textFormat = OpenTelemetry.getPropagators().getTextMapPropagator();;

  // Extract the Distributed Context from the gRPC metadata
  TextMapPropagator.Getter<Metadata> getter =
      new TextMapPropagator.Getter<Metadata>() {
        @Override
        public String get(Metadata carrier, String key) {
          Metadata.Key<String> k = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
          if (carrier.containsKey(k)) {
            return carrier.get(k);
          }
          return "";
        }
      };

  public HelloWorldServer() {
    initTracer();
  }

  private void start() throws IOException {
    /* The port on which the server should run */

    server =
        ServerBuilder.forPort(this.port)
            .addService(new GreeterImpl())
            // Intercept gRPC calls
            .intercept(new OpenTelemetryServerInterceptor())
            .build()
            .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                  System.err.println("*** shutting down gRPC server since JVM is shutting down");
                  HelloWorldServer.this.stop();
                  System.err.println("*** server shut down");
                  System.err.println(
                      "*** forcing also the Tracer Exporter to shutdown and process the remaining traces");
                  exporter.shutdown();
                  System.err.println("*** Trace Exporter shut down");
                }));
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  private void initTracer() {
    // Get the tracer
    TracerSdkProvider tracerProvider = OpenTelemetrySdk.getTracerProvider();
    // Set to process the the spans by the LogExporter
    tracerProvider.addSpanProcessor(SimpleSpanProcessor.newBuilder(exporter).build());
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    // We serve a normal gRPC call
    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      // Serve the request
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }

    // We serve a stream gRPC call
    @Override
    public StreamObserver<HelloRequest> sayHelloStream(
        final StreamObserver<HelloReply> responseObserver) {
      return new StreamObserver<HelloRequest>() {
        @Override
        public void onNext(HelloRequest value) {
          responseObserver.onNext(
              HelloReply.newBuilder().setMessage("Hello " + value.getName()).build());
        }

        @Override
        public void onError(Throwable t) {
          logger.info("[Error] " + t.getMessage());
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }

  private class OpenTelemetryServerInterceptor implements io.grpc.ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      // Extract the Span Context from the metadata of the gRPC request
      Context extractedContext = textFormat.extract(Context.current(), headers, getter);
      InetSocketAddress clientInfo =
          (InetSocketAddress) call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
      // Build a span based on the received context
      try (Scope scope = ContextUtils.withScopedContext(extractedContext)) {
        Span span =
            tracer
                .spanBuilder("helloworld.Greeter/SayHello")
                .setSpanKind(Span.Kind.SERVER)
                .startSpan();
        span.setAttribute("component", "grpc");
        span.setAttribute("rpc.service", "Greeter");
        span.setAttribute("net.peer.ip", clientInfo.getHostString());
        span.setAttribute("net.peer.port", clientInfo.getPort());
        // Process the gRPC call normally
        try {
          span.setStatus(Status.OK);
          return Contexts.interceptCall(Context.current(), call, headers, next);
        } finally {
          span.end();
        }
      }
    }
  }

  /** Main launches the server from the command line. */
  public static void main(String[] args) throws IOException, InterruptedException {
    final HelloWorldServer server = new HelloWorldServer();
    server.start();
    server.blockUntilShutdown();
  }
}
