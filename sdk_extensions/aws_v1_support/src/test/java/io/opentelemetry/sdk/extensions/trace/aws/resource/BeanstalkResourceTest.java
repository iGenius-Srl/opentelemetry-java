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

import static io.opentelemetry.common.AttributeValue.stringAttributeValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import io.opentelemetry.common.Attributes;
import io.opentelemetry.sdk.resources.ResourceAttributes;
import io.opentelemetry.sdk.resources.ResourceProvider;
import java.io.File;
import java.io.IOException;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BeanstalkResourceTest {

  @Test
  void testCreateAttributes(@TempDir File tempFolder) throws IOException {
    File file = new File(tempFolder, "beanstalk.config");
    String content =
        "{\"noise\": \"noise\", \"deployment_id\":4,\""
            + "version_label\":\"2\",\"environment_name\":\"HttpSubscriber-env\"}";
    Files.write(content.getBytes(Charsets.UTF_8), file);
    BeanstalkResource populator = new BeanstalkResource(file.getPath());
    Attributes attributes = populator.getAttributes();
    assertThat(attributes)
        .isEqualTo(
            Attributes.of(
                ResourceAttributes.SERVICE_INSTANCE.key(), stringAttributeValue("4"),
                ResourceAttributes.SERVICE_VERSION.key(), stringAttributeValue("2"),
                ResourceAttributes.SERVICE_NAMESPACE.key(),
                    stringAttributeValue("HttpSubscriber-env")));
  }

  @Test
  void testConfigFileMissing() throws IOException {
    BeanstalkResource populator = new BeanstalkResource("a_file_never_existing");
    Attributes attributes = populator.getAttributes();
    assertThat(attributes.isEmpty()).isTrue();
  }

  @Test
  void testBadConfigFile(@TempDir File tempFolder) throws IOException {
    File file = new File(tempFolder, "beanstalk.config");
    String content =
        "\"deployment_id\":4,\"version_label\":\"2\",\""
            + "environment_name\":\"HttpSubscriber-env\"}";
    Files.write(content.getBytes(Charsets.UTF_8), file);
    BeanstalkResource populator = new BeanstalkResource(file.getPath());
    Attributes attributes = populator.getAttributes();
    assertThat(attributes.isEmpty()).isTrue();
  }

  @Test
  void inServiceLoader() {
    // No practical way to test the attributes themselves so at least check the service loader picks
    // it up.
    assertThat(ServiceLoader.load(ResourceProvider.class))
        .anyMatch(BeanstalkResource.class::isInstance);
  }
}
