package io.opentelemetry.exporters.zipkin; /*
                                            * Copyright 2016-2019 The OpenZipkin Authors
                                            *
                                            * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
                                            * in compliance with the License. You may obtain a copy of the License at
                                            *
                                            * http://www.apache.org/licenses/LICENSE-2.0
                                            *
                                            * Unless required by applicable law or agreed to in writing, software distributed under the License
                                            * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
                                            * or implied. See the License for the specific language governing permissions and limitations under
                                            * the License.
                                            */


import java.io.IOException;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import zipkin2.Call;
import zipkin2.CheckResult;
import zipkin2.codec.Encoding;
import zipkin2.internal.Platform;
import zipkin2.reporter.ClosedSenderException;
import zipkin2.reporter.Sender;


public final class ZipkinSpanExporterSender2 extends Sender {

  public String token;

  /** Creates a sender that posts {@link Encoding#JSON} messages. */
  public static ZipkinSpanExporterSender2 create(String endpoint, String token) {
    return newBuilder().endpoint(endpoint).authToken(token).build();
  }

  public static Builder newBuilder() {
    return new Builder(new OkHttpClient.Builder());
  }

  public static final class Builder {
    final OkHttpClient.Builder clientBuilder;
    HttpUrl endpoint;
    Encoding encoding = Encoding.JSON;
    String token;
    boolean compressionEnabled = true;
    int maxRequests = 64;
    int messageMaxBytes = 500_000;

    Builder(OkHttpClient.Builder clientBuilder) {
      this.clientBuilder = clientBuilder;
    }

    Builder(ZipkinSpanExporterSender2 sender) {
      clientBuilder = sender.client.newBuilder();
      endpoint = sender.endpoint;
      maxRequests = sender.client.dispatcher().getMaxRequests();
      compressionEnabled = sender.compressionEnabled;
      encoding = sender.encoding;
      messageMaxBytes = sender.messageMaxBytes;
    }

    /**
     * No default. The POST URL for zipkin's <a href="https://zipkin.io/zipkin-api/#/">v2 api</a>,
     * usually "http://zipkinhost:9411/api/v2/spans"
     */
    // customizable so that users can re-map /api/v2/spans ex for browser-originated traces
    public Builder endpoint(String endpoint) {
      if (endpoint == null) throw new NullPointerException("endpoint == null");
      HttpUrl parsed = HttpUrl.parse(endpoint);
      if (parsed == null) throw new IllegalArgumentException("invalid post url: " + endpoint);
      return endpoint(parsed);
    }

    public Builder endpoint(HttpUrl endpoint) {
      if (endpoint == null) throw new NullPointerException("endpoint == null");
      this.endpoint = endpoint;
      return this;
    }

    /** Default true. true implies that spans will be gzipped before transport. */
    public Builder compressionEnabled(boolean compressionEnabled) {
      this.compressionEnabled = compressionEnabled;
      return this;
    }

    /** Maximum size of a message. Default 500KB */
    public Builder messageMaxBytes(int messageMaxBytes) {
      this.messageMaxBytes = messageMaxBytes;
      return this;
    }

    /** Maximum in-flight requests. Default 64 */
    public Builder maxRequests(int maxRequests) {
      this.maxRequests = maxRequests;
      return this;
    }

    /**
     * Use this to change the encoding used in messages. Default is {@linkplain Encoding#JSON} This
     * also controls the "Content-Type" header when sending spans.
     *
     * <p>Note: If ultimately sending to Zipkin, version 2.8+ is required to process protobuf.
     */
    public Builder encoding(Encoding encoding) {
      if (encoding == null) throw new NullPointerException("encoding == null");
      this.encoding = encoding;
      return this;
    }

    /** Default is an empty string. It is needed in case of authorized calls */
    public Builder authToken(String token) {
      this.token = token;
      return this;
    }

    public OkHttpClient.Builder clientBuilder() {
      return clientBuilder;
    }

    public final ZipkinSpanExporterSender2 build() {
      return new ZipkinSpanExporterSender2(this);
    }
  }

  final HttpUrl endpoint;
  final OkHttpClient client;
  final RequestBodyMessageEncoder encoder;
  final Encoding encoding;
  final int messageMaxBytes;
  final int maxRequests;
  final boolean compressionEnabled;

  ZipkinSpanExporterSender2(Builder builder) {
    if (builder.endpoint == null) throw new NullPointerException("endpoint == null");
    endpoint = builder.endpoint;
    encoding = builder.encoding;
    switch (encoding) {
      case JSON:
        encoder = RequestBodyMessageEncoder.JSON;
        break;
      case PROTO3:
        encoder = RequestBodyMessageEncoder.PROTO3;
        break;
      default:
        throw new UnsupportedOperationException("Unsupported encoding: " + encoding.name());
    }
    maxRequests = builder.maxRequests;
    messageMaxBytes = builder.messageMaxBytes;
    compressionEnabled = builder.compressionEnabled;
    Dispatcher dispatcher = newDispatcher(maxRequests);

    // doing the extra "build" here prevents us from leaking our dispatcher to the builder
    client = builder.clientBuilder().build().newBuilder().dispatcher(dispatcher).build();
  }

  static Dispatcher newDispatcher(int maxRequests) {
    // bound the executor so that we get consistent performance
    SynchronousQueue<Runnable> blockingQueue = new SynchronousQueue<>();
    ThreadPoolExecutor dispatchExecutor =
        new ThreadPoolExecutor(
            0,
            maxRequests,
            60,
            TimeUnit.SECONDS,
            // Using a synchronous queue means messages will send immediately until we hit max
            // in-flight requests. Once max requests are hit, send will block the caller, which is
            // the AsyncReporter flush thread. This is ok, as the AsyncReporter has a buffer of
            // unsent spans for this purpose.
            blockingQueue,
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                return new Thread(r, "OkHttpSender Dispatcher");
              }
            });

    Dispatcher dispatcher = new Dispatcher(dispatchExecutor);
    dispatcher.setMaxRequests(maxRequests);
    dispatcher.setMaxRequestsPerHost(maxRequests);
    return dispatcher;
  }

  /**
   * Creates a builder out of this object. Note: if the {@link Builder#clientBuilder()} was
   * customized, you'll need to re-apply those customizations.
   */
  public final Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public int messageSizeInBytes(List<byte[]> encodedSpans) {
    return encoding.listSizeInBytes(encodedSpans);
  }

  @Override
  public int messageSizeInBytes(int encodedSizeInBytes) {
    return encoding.listSizeInBytes(encodedSizeInBytes);
  }

  @Override
  public Encoding encoding() {
    return encoding;
  }

  @Override
  public int messageMaxBytes() {
    return messageMaxBytes;
  }

  /** close is typically called from a different thread */
  volatile boolean closeCalled;

  /** The returned call sends spans as a POST to {@link Builder#endpoint(String)}. */
  @Override
  public Call<Void> sendSpans(List<byte[]> encodedSpans) {
    if (closeCalled) throw new ClosedSenderException();
    Request request;
    try {
      request = newRequest(encoder.encode(encodedSpans));
    } catch (IOException e) {
      throw Platform.get().uncheckedIOException(e);
    }
    return new HttpCall(client.newCall(request));
  }

  /** Sends an empty json message to the configured endpoint. */
  @Override
  public CheckResult check() {
    try {
      Request request =
          new Request.Builder()
              .url(endpoint)
              .post(RequestBody.create(MediaType.parse("application/json"), "[]"))
              .build();
      try (Response response = client.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          return CheckResult.failed(new RuntimeException("check response failed: " + response));
        }
      }
      return CheckResult.OK;
    } catch (Exception e) {
      return CheckResult.failed(e);
    }
  }

  /** Waits up to a second for in-flight requests to finish before cancelling them */
  @Override
  public synchronized void close() {
    if (closeCalled) return;
    closeCalled = true;

    Dispatcher dispatcher = client.dispatcher();
    dispatcher.executorService().shutdown();
    try {
      if (!dispatcher.executorService().awaitTermination(1, TimeUnit.SECONDS)) {
        dispatcher.cancelAll();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  Request newRequest(RequestBody body) throws IOException {
    Request.Builder request = new Request.Builder().url(endpoint);
    if (compressionEnabled) {
      request.addHeader("Content-Encoding", "gzip");
      Buffer gzipped = new Buffer();
      BufferedSink gzipSink = Okio.buffer(new GzipSink(gzipped));
      body.writeTo(gzipSink);
      gzipSink.close();
      body = new BufferRequestBody(body.contentType(), gzipped);
    }
    if (!token.isEmpty()) {
      request.addHeader("Authorization", token);
    }
    request.post(body);
    return request.build();
  }

  @Override
  public final String toString() {
    return "OkHttpSender{" + endpoint + "}";
  }

  static final class BufferRequestBody extends RequestBody {
    final MediaType contentType;
    final Buffer body;

    BufferRequestBody(MediaType contentType, Buffer body) {
      this.contentType = contentType;
      this.body = body;
    }

    @Override
    public long contentLength() {
      return body.size();
    }

    @Override
    public MediaType contentType() {
      return contentType;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      sink.write(body, body.size());
    }
  }
}
