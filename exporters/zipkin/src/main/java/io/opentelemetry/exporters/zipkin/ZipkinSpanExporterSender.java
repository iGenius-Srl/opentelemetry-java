/*
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
package io.opentelemetry.exporters.zipkin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import zipkin2.Call;
import zipkin2.Callback;
import zipkin2.CheckResult;
import zipkin2.codec.Encoding;
import zipkin2.reporter.BytesMessageEncoder;
import zipkin2.reporter.ClosedSenderException;
import zipkin2.reporter.Sender;

/**
 * Reports spans to Zipkin, using its <a href="https://zipkin.io/zipkin-api/#/">POST</a> endpoint.
 *
 * <p>This sender is thread-safe.
 */
public final class ZipkinSpanExporterSender extends Sender {
  /**
   * Creates a sender that posts {@link Encoding#JSON} messages.
   *
   * @param endpoint random
   * @return ZipkinSpanExporterSender
   */
  public static ZipkinSpanExporterSender create(String endpoint, String token) {
    return newBuilder().endpoint(endpoint).authToken(token).build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    URL endpoint;
    Encoding encoding = Encoding.JSON;
    int messageMaxBytes = 500_000;
    int connectTimeout = 10 * 1000;
    int readTimeout = 60 * 1000;
    String token;
    boolean compressionEnabled = true;

    Builder(ZipkinSpanExporterSender sender) {
      this.endpoint = sender.endpoint;
      this.token = sender.token;
      this.encoding = sender.encoding;
      this.messageMaxBytes = sender.messageMaxBytes;
      this.connectTimeout = sender.connectTimeout;
      this.readTimeout = sender.readTimeout;
      this.compressionEnabled = sender.compressionEnabled;
    }

    Builder() {}

    /**
     * No default. The POST URL for zipkin's <a href="https://zipkin.io/zipkin-api/#/">v2 api</a>,
     * usually "http://zipkinhost:9411/api/v2/spans"
     */
    // customizable so that users can re-map /api/v2/spans ex for browser-originated traces
    public final Builder endpoint(String endpoint) {
      if (endpoint == null) throw new NullPointerException("endpoint == null");

      try {
        return endpoint(new URL(endpoint));
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException(e.getMessage());
      }
    }

    public Builder endpoint(URL endpoint) {
      if (endpoint == null) throw new NullPointerException("endpoint == null");
      this.endpoint = endpoint;
      return this;
    }

    /** Default 10 * 1000 milliseconds. 0 implies no timeout. */
    public Builder connectTimeout(int connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    /** Default is an empty string. It is needed in case of authorized calls */
    public Builder authToken(String token) {
      this.token = token;
      return this;
    }

    /** Default 60 * 1000 milliseconds. 0 implies no timeout. */
    public Builder readTimeout(int readTimeout) {
      this.readTimeout = readTimeout;
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

    public final ZipkinSpanExporterSender build() {
      return new ZipkinSpanExporterSender(this);
    }
  }

  final URL endpoint;
  final Encoding encoding;
  final String mediaType;
  final BytesMessageEncoder encoder;
  final String token;
  final int messageMaxBytes;
  final int connectTimeout;
  final int readTimeout;
  final boolean compressionEnabled;

  ZipkinSpanExporterSender(Builder builder) {
    if (builder.endpoint == null) throw new NullPointerException("endpoint == null");
    this.endpoint = builder.endpoint;
    this.encoding = builder.encoding;
    switch (builder.encoding) {
      case JSON:
        this.mediaType = "application/json";
        this.encoder = BytesMessageEncoder.JSON;
        break;
      case PROTO3:
        this.mediaType = "application/x-protobuf";
        this.encoder = BytesMessageEncoder.PROTO3;
        break;
      default:
        throw new UnsupportedOperationException("Unsupported encoding: " + encoding.name());
    }
    this.messageMaxBytes = builder.messageMaxBytes;
    this.connectTimeout = builder.connectTimeout;
    this.readTimeout = builder.readTimeout;
    this.compressionEnabled = builder.compressionEnabled;
    this.token = builder.token;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  /** close is typically called from a different thread */
  volatile boolean closeCalled;

  @Override
  public int messageSizeInBytes(List<byte[]> encodedSpans) {
    return encoding().listSizeInBytes(encodedSpans);
  }

  @Override
  public int messageSizeInBytes(int encodedSizeInBytes) {
    return encoding().listSizeInBytes(encodedSizeInBytes);
  }

  @Override
  public Encoding encoding() {
    return encoding;
  }

  @Override
  public int messageMaxBytes() {
    return messageMaxBytes;
  }

  /** The returned call sends spans as a POST to {@link Builder#endpoint}. */
  @Override
  public Call<Void> sendSpans(List<byte[]> encodedSpans) {
    if (closeCalled) throw new ClosedSenderException();
    return new HttpPostCall(encoder.encode(encodedSpans));
  }

  /** Sends an empty json message to the configured endpoint. */
  @Override
  public CheckResult check() {
    try {
      send(new byte[] {'[', ']'}, "application/json");
      return CheckResult.OK;
    } catch (Throwable e) {
      Call.propagateIfFatal(e);
      return CheckResult.failed(e);
    }
  }

  @Override
  public void close() {
    closeCalled = true;
  }

  void send(byte[] body, String mediaType) throws IOException {
    // intentionally not closing the connection, so as to use keep-alives
    HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
    connection.setConnectTimeout(connectTimeout);
    connection.setReadTimeout(readTimeout);
    connection.setRequestMethod("POST");
    connection.addRequestProperty("Content-Type", mediaType);
    if (!token.isEmpty()) {
      connection.addRequestProperty("Authorization", token);
    }
    if (compressionEnabled) {
      connection.addRequestProperty("Content-Encoding", "gzip");
      ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
      GZIPOutputStream compressor = new GZIPOutputStream(gzipped);
      try {
        compressor.write(body);
      } finally {
        compressor.close();
      }
      body = gzipped.toByteArray();
    }
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(body.length);
    connection.getOutputStream().write(body);

    skipAllContent(connection);
  }

  /** This utility is verbose as we have a minimum java version of 6 */
  static void skipAllContent(HttpURLConnection connection) throws IOException {
    InputStream in = connection.getInputStream();
    IOException thrown = skipAndSuppress(in);
    if (thrown == null) return;
    InputStream err = connection.getErrorStream();
    if (err != null) skipAndSuppress(err); // null is possible, if the connection was dropped
    throw thrown;
  }

  @Nullable
  static IOException skipAndSuppress(InputStream in) {
    try {
      while (in.read() != -1) ; // skip
      return null;
    } catch (IOException e) {
      return e;
    } finally {
      try {
        in.close();
      } catch (IOException suppressed) {
      }
    }
  }

  @Override
  public final String toString() {
    return "URLConnectionSender{" + endpoint + "}";
  }

  class HttpPostCall extends Call.Base<Void> {
    private final byte[] message;

    HttpPostCall(byte[] message) {
      this.message = message;
    }

    @Override
    protected Void doExecute() throws IOException {
      send(message, mediaType);
      return null;
    }

    @Override
    protected void doEnqueue(Callback<Void> callback) {
      try {
        send(message, mediaType);
        callback.onSuccess(null);
      } catch (IOException | RuntimeException | Error e) {
        callback.onError(e);
      }
    }

    @Override
    public Call<Void> clone() {
      return new HttpPostCall(message);
    }
  }
}
