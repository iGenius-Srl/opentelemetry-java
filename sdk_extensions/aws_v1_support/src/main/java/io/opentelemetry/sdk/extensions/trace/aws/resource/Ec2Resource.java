/*
 * Copyright 2020, OpenTelemetry Authors
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

package io.opentelemetry.sdk.extensions.trace.aws.resource;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;
import io.opentelemetry.common.Attributes;
import io.opentelemetry.sdk.resources.ResourceAttributes;
import io.opentelemetry.sdk.resources.ResourceProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link ResourceProvider} which provides information about the current EC2 instance if running
 * on AWS EC2.
 */
public class Ec2Resource extends ResourceProvider {

  private static final Logger logger = Logger.getLogger(Ec2Resource.class.getName());

  private static final int TIMEOUT_MILLIS = 2000;

  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private static final String DEFAULT_IMDS_ENDPOINT = "169.254.169.254";

  private final URL identityDocumentUrl;
  private final URL hostnameUrl;
  private final URL tokenUrl;

  /**
   * Returns a {@link Ec2Resource} which attempts to compute information about this instance if
   * available.
   */
  public Ec2Resource() {
    // This is only for testing e.g., with a mock IMDS server and never in production so we just
    // read from a system property. This is similar to the AWS SDK.
    this(System.getProperty("otel.aws.imds.endpointOverride", DEFAULT_IMDS_ENDPOINT));
  }

  @VisibleForTesting
  Ec2Resource(String endpoint) {
    String urlBase = "http://" + endpoint;
    try {
      this.identityDocumentUrl = new URL(urlBase + "/latest/dynamic/instance-identity/document");
      this.hostnameUrl = new URL(urlBase + "/latest/meta-data/hostname");
      this.tokenUrl = new URL(urlBase + "/latest/api/token");
    } catch (MalformedURLException e) {
      // Can only happen when overriding the endpoint in testing so just throw.
      throw new IllegalArgumentException("Illegal endpoint: " + endpoint, e);
    }
  }

  // Generic HTTP fetch function for IMDS.
  private static String fetchString(String httpMethod, URL url, String token, boolean includeTtl) {
    final HttpURLConnection connection;
    try {
      connection = (HttpURLConnection) url.openConnection();
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error connecting to IMDS.", e);
      return "";
    }

    try {
      connection.setRequestMethod(httpMethod);
    } catch (ProtocolException e) {
      logger.log(Level.WARNING, "Unknown HTTP method, this is a programming bug.", e);
      return "";
    }

    connection.setConnectTimeout(TIMEOUT_MILLIS);
    connection.setReadTimeout(TIMEOUT_MILLIS);

    if (includeTtl) {
      connection.setRequestProperty("X-aws-ec2-metadata-token-ttl-seconds", "60");
    }
    if (!token.isEmpty()) {
      connection.setRequestProperty("X-aws-ec2-metadata-token", token);
    }

    final int responseCode;
    try {
      responseCode = connection.getResponseCode();
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error connecting to IMDS: ", e);
      return "";
    }

    if (responseCode != 200) {
      logger.log(
          Level.WARNING,
          "Error reponse from IMDS: code ("
              + responseCode
              + ") text "
              + readResponseString(connection));
    }

    return readResponseString(connection).trim();
  }

  private static String readResponseString(HttpURLConnection connection) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (InputStream is = connection.getInputStream()) {
      ByteStreams.copy(is, os);
    } catch (IOException e) {
      // Only best effort read if we can.
    }
    try (InputStream is = connection.getErrorStream()) {
      if (is != null) {
        ByteStreams.copy(is, os);
      }
    } catch (IOException e) {
      // Only best effort read if we can.
    }
    try {
      return os.toString(StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8 not supported can't happen.", e);
    }
  }

  @Override
  public Attributes getAttributes() {
    String token = fetchToken();

    // If token is empty, either IMDSv2 isn't enabled or an unexpected failure happened. We can
    // still get data if IMDSv1 is enabled.
    String identity = fetchIdentity(token);
    if (identity.isEmpty()) {
      // If no identity document, assume we are not actually running on EC2.
      return Attributes.empty();
    }

    String hostname = fetchHostname(token);

    Attributes.Builder attrBuilders = Attributes.newBuilder();

    try (JsonParser parser = JSON_FACTORY.createParser(identity)) {
      parser.nextToken();

      if (!parser.isExpectedStartObjectToken()) {
        throw new IOException("Invalid JSON:" + identity);
      }

      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String value = parser.nextTextValue();
        switch (parser.getCurrentName()) {
          case "instanceId":
            ResourceAttributes.HOST_ID.set(attrBuilders, value);
            break;
          case "availabilityZone":
            ResourceAttributes.CLOUD_ZONE.set(attrBuilders, value);
            break;
          case "instanceType":
            ResourceAttributes.HOST_TYPE.set(attrBuilders, value);
            break;
          case "imageId":
            ResourceAttributes.HOST_IMAGE_ID.set(attrBuilders, value);
            break;
          case "accountId":
            ResourceAttributes.CLOUD_ACCOUNT.set(attrBuilders, value);
            break;
          case "region":
            ResourceAttributes.CLOUD_REGION.set(attrBuilders, value);
            break;
          default:
            parser.skipChildren();
        }
      }
    } catch (IOException e) {
      logger.log(Level.WARNING, "Could not parse identity document, resource not filled.", e);
      return Attributes.empty();
    }

    ResourceAttributes.HOST_HOSTNAME.set(attrBuilders, hostname);
    ResourceAttributes.HOST_NAME.set(attrBuilders, hostname);

    return attrBuilders.build();
  }

  private String fetchToken() {
    return fetchString("PUT", tokenUrl, "", /* includeTtl= */ true);
  }

  private String fetchIdentity(String token) {
    return fetchString("GET", identityDocumentUrl, token, /* includeTtl= */ false);
  }

  private String fetchHostname(String token) {
    return fetchString("GET", hostnameUrl, token, /* includeTtl= */ false);
  }
}
