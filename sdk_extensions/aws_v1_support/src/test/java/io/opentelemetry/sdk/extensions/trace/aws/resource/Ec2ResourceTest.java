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

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import io.opentelemetry.common.Attributes;
import io.opentelemetry.sdk.resources.ResourceAttributes;
import io.opentelemetry.sdk.resources.ResourceProvider;
import java.util.ServiceLoader;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

public class Ec2ResourceTest {

  // From https://docs.amazonaws.cn/en_us/AWSEC2/latest/UserGuide/instance-identity-documents.html
  private static final String IDENTITY_DOCUMENT =
      "{\n"
          + "    \"devpayProductCodes\" : null,\n"
          + "    \"marketplaceProductCodes\" : [ \"1abc2defghijklm3nopqrs4tu\" ], \n"
          + "    \"availabilityZone\" : \"us-west-2b\",\n"
          + "    \"privateIp\" : \"10.158.112.84\",\n"
          + "    \"version\" : \"2017-09-30\",\n"
          + "    \"instanceId\" : \"i-1234567890abcdef0\",\n"
          + "    \"billingProducts\" : null,\n"
          + "    \"instanceType\" : \"t2.micro\",\n"
          + "    \"accountId\" : \"123456789012\",\n"
          + "    \"imageId\" : \"ami-5fb8c835\",\n"
          + "    \"pendingTime\" : \"2016-11-19T16:32:11Z\",\n"
          + "    \"architecture\" : \"x86_64\",\n"
          + "    \"kernelId\" : null,\n"
          + "    \"ramdiskId\" : null,\n"
          + "    \"region\" : \"us-west-2\"\n"
          + "}";

  @ClassRule
  public static WireMockClassRule server = new WireMockClassRule(wireMockConfig().dynamicPort());

  private Ec2Resource populator;

  @Before
  public void setUp() {
    populator = new Ec2Resource("localhost:" + server.port());
  }

  @Test
  public void imdsv2() {
    stubFor(any(urlPathEqualTo("/latest/api/token")).willReturn(ok("token")));
    stubFor(
        any(urlPathEqualTo("/latest/dynamic/instance-identity/document"))
            .willReturn(okJson(IDENTITY_DOCUMENT)));
    stubFor(any(urlPathEqualTo("/latest/meta-data/hostname")).willReturn(ok("ec2-1-2-3-4")));

    Attributes attributes = populator.getAttributes();
    Attributes.Builder expectedAttrBuilders = Attributes.newBuilder();

    ResourceAttributes.HOST_ID.set(expectedAttrBuilders, "i-1234567890abcdef0");
    ResourceAttributes.CLOUD_ZONE.set(expectedAttrBuilders, "us-west-2b");
    ResourceAttributes.HOST_TYPE.set(expectedAttrBuilders, "t2.micro");
    ResourceAttributes.HOST_IMAGE_ID.set(expectedAttrBuilders, "ami-5fb8c835");
    ResourceAttributes.CLOUD_ACCOUNT.set(expectedAttrBuilders, "123456789012");
    ResourceAttributes.CLOUD_REGION.set(expectedAttrBuilders, "us-west-2");
    ResourceAttributes.HOST_HOSTNAME.set(expectedAttrBuilders, "ec2-1-2-3-4");
    ResourceAttributes.HOST_NAME.set(expectedAttrBuilders, "ec2-1-2-3-4");
    assertThat(attributes).isEqualTo(expectedAttrBuilders.build());

    verify(
        putRequestedFor(urlEqualTo("/latest/api/token"))
            .withHeader("X-aws-ec2-metadata-token-ttl-seconds", equalTo("60")));
    verify(
        getRequestedFor(urlEqualTo("/latest/dynamic/instance-identity/document"))
            .withHeader("X-aws-ec2-metadata-token", equalTo("token")));
    verify(
        getRequestedFor(urlEqualTo("/latest/meta-data/hostname"))
            .withHeader("X-aws-ec2-metadata-token", equalTo("token")));
  }

  @Test
  public void imdsv1() {
    stubFor(any(urlPathEqualTo("/latest/api/token")).willReturn(notFound()));
    stubFor(
        any(urlPathEqualTo("/latest/dynamic/instance-identity/document"))
            .willReturn(okJson(IDENTITY_DOCUMENT)));
    stubFor(any(urlPathEqualTo("/latest/meta-data/hostname")).willReturn(ok("ec2-1-2-3-4")));

    Attributes attributes = populator.getAttributes();
    Attributes.Builder expectedAttrBuilders = Attributes.newBuilder();

    ResourceAttributes.HOST_ID.set(expectedAttrBuilders, "i-1234567890abcdef0");
    ResourceAttributes.CLOUD_ZONE.set(expectedAttrBuilders, "us-west-2b");
    ResourceAttributes.HOST_TYPE.set(expectedAttrBuilders, "t2.micro");
    ResourceAttributes.HOST_IMAGE_ID.set(expectedAttrBuilders, "ami-5fb8c835");
    ResourceAttributes.CLOUD_ACCOUNT.set(expectedAttrBuilders, "123456789012");
    ResourceAttributes.CLOUD_REGION.set(expectedAttrBuilders, "us-west-2");
    ResourceAttributes.HOST_HOSTNAME.set(expectedAttrBuilders, "ec2-1-2-3-4");
    ResourceAttributes.HOST_NAME.set(expectedAttrBuilders, "ec2-1-2-3-4");
    assertThat(attributes).isEqualTo(expectedAttrBuilders.build());

    verify(
        putRequestedFor(urlEqualTo("/latest/api/token"))
            .withHeader("X-aws-ec2-metadata-token-ttl-seconds", equalTo("60")));
    verify(
        getRequestedFor(urlEqualTo("/latest/dynamic/instance-identity/document"))
            .withoutHeader("X-aws-ec2-metadata-token"));
  }

  @Test
  public void badJson() {
    stubFor(any(urlPathEqualTo("/latest/api/token")).willReturn(notFound()));
    stubFor(
        any(urlPathEqualTo("/latest/dynamic/instance-identity/document"))
            .willReturn(okJson("I'm not JSON")));

    Attributes attributes = populator.getAttributes();
    assertThat(attributes.isEmpty()).isTrue();

    verify(
        putRequestedFor(urlEqualTo("/latest/api/token"))
            .withHeader("X-aws-ec2-metadata-token-ttl-seconds", equalTo("60")));
    verify(
        getRequestedFor(urlEqualTo("/latest/dynamic/instance-identity/document"))
            .withoutHeader("X-aws-ec2-metadata-token"));
  }

  @Test
  public void inServiceLoader() {
    // No practical way to test the attributes themselves so at least check the service loader picks
    // it up.
    assertThat(ServiceLoader.load(ResourceProvider.class)).anyMatch(Ec2Resource.class::isInstance);
  }
}
