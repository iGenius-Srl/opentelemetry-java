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

package io.opentelemetry.sdk.common.export;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Base class for all the config builder classes (SimpleSpanProcessor, BatchSpanProcessor, etc).
 *
 * <p>The type parameter on this class should be set to the type of the implementation of this
 * abstract class. For example, if your subclass is a Foo.Builder, then you would declare it as
 *
 * <p>{@code Foo.Builder extends ConfigBuilder<Foo.Builder>}
 *
 * @param <T> the type of the implementation.
 */
public abstract class ConfigBuilder<T> {

  @VisibleForTesting
  protected enum NamingConvention {
    DOT {
      @Override
      public String normalize(@Nonnull String key) {
        return key.toLowerCase();
      }
    },
    ENV_VAR {
      @Override
      public String normalize(@Nonnull String key) {
        return key.toLowerCase().replace("_", ".");
      }
    };

    public abstract String normalize(@Nonnull String key);

    /**
     * Normalize the key value of the map using the class {@link #normalize(String)} method.
     *
     * @see #normalize(String)
     * @param map The map to normalize
     * @return an unmodifiable normalized map
     */
    public Map<String, String> normalize(@Nonnull Map<String, String> map) {
      Map<String, String> properties = new HashMap<>();
      for (Map.Entry<String, String> entry : map.entrySet()) {
        properties.put(normalize(entry.getKey()), entry.getValue());
      }
      return Collections.unmodifiableMap(properties);
    }
  }

  /** Sets the configuration values from the given configuration map for only the available keys. */
  protected abstract T fromConfigMap(
      Map<String, String> configMap, NamingConvention namingConvention);

  /** Sets the configuration values from the given {@link Properties} object. */
  public T readProperties(Properties properties) {
    return fromConfigMap(Maps.fromProperties(properties), NamingConvention.DOT);
  }

  /** Sets the configuration values from environment variables. */
  public T readEnvironmentVariables() {
    return fromConfigMap(System.getenv(), NamingConvention.ENV_VAR);
  }

  /** Sets the configuration values from system properties. */
  public T readSystemProperties() {
    return readProperties(System.getProperties());
  }

  /**
   * Get a boolean property from the map, {@code null} if it cannot be found or it has a wrong type.
   *
   * @param name The property name
   * @param map The map where to look for the property
   * @return the {@link Boolean} value of the property, {@code null} in case of error or if the
   *     property cannot be found.
   */
  @Nullable
  protected static Boolean getBooleanProperty(String name, Map<String, String> map) {
    if (map.containsKey(name)) {
      return Boolean.parseBoolean(map.get(name));
    }
    return null;
  }

  /**
   * Get an integer property from the map, {@code null} if it cannot be found or it has a wrong
   * type.
   *
   * @param name The property name
   * @param map The map where to look for the property
   * @return the {@link Integer} value of the property, {@code null} in case of error or if the
   *     property cannot be found.
   */
  @Nullable
  protected static Integer getIntProperty(String name, Map<String, String> map) {
    try {
      return Integer.parseInt(map.get(name));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Get a long property from the map, {@code null} if it cannot be found or it has a wrong type.
   *
   * @param name The property name
   * @param map The map where to look for the property
   * @return the {@link Long} value of the property, {@code null} in case of error or if the
   *     property cannot be found.
   */
  @Nullable
  protected static Long getLongProperty(String name, Map<String, String> map) {
    try {
      return Long.parseLong(map.get(name));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /**
   * Get a {@link String} property from the map, {@code null} if it cannot be found.
   *
   * @param name The property name
   * @param map The map where to look for the property
   * @return the {@link String} value of the property, {@code null} if the property cannot be found.
   */
  @Nullable
  protected static String getStringProperty(String name, Map<String, String> map) {
    return map.get(name);
  }

  /**
   * Get a double property from the map, {@code null} if it cannot be found or it has a wrong type.
   *
   * @param name The property name
   * @param map The map where to look for the property
   * @return the {@link Double} value of the property, {@code null} in case of error or if the
   *     property cannot be found.
   */
  @Nullable
  protected static Double getDoubleProperty(String name, Map<String, String> map) {
    try {
      return Double.parseDouble(map.get(name));
    } catch (NumberFormatException | NullPointerException ex) {
      return null;
    }
  }
}
