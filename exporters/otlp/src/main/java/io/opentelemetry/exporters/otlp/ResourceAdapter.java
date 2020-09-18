/// *
// * Copyright 2020, OpenTelemetry Authors
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
// package io.opentelemetry.exporters.otlp;
//
// import io.opentelemetry.common.AttributeValue;
// import io.opentelemetry.common.ReadableKeyValuePairs.KeyValueConsumer;
// import io.opentelemetry.proto.resource.v1.Resource;
//
// final class ResourceAdapter {
//  static Resource toProtoResource(io.opentelemetry.sdk.resources.Resource resource) {
//    final Resource.Builder builder = Resource.newBuilder();
//    resource
//        .getAttributes()
//        .forEach(
//            new KeyValueConsumer<AttributeValue>() {
//              @Override
//              public void consume(String key, AttributeValue value) {
//                builder.addAttributes(CommonAdapter.toProtoAttribute(key, value));
//              }
//            });
//    return builder.build();
//  }
//
//  private ResourceAdapter() {}
// }
