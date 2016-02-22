/*
 * Copyright Terracotta, Inc.
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
package org.terracotta.management.stats;

import org.terracotta.management.Objects;

import java.io.Serializable;

/**
 * @author Ludovic Orban
 * @author Mathieu Carbou
 */
public final class Sample<V extends Serializable> implements Serializable {

  private final long timestamp;
  private final V value;

  public Sample(long timestamp, V value) {
    this.timestamp = timestamp;
    this.value = Objects.requireNonNull(value);
  }

  public long getTimestamp() {
    return timestamp;
  }

  public V getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Sample<?> sample = (Sample<?>) o;

    if (timestamp != sample.timestamp) return false;
    return value.equals(sample.value);

  }

  @Override
  public int hashCode() {
    int result = (int) (timestamp ^ (timestamp >>> 32));
    result = 31 * result + value.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "{timestamp=" + timestamp + ", value=" + value + '}';
  }
}
