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

package io.opentelemetry.sdk.metrics;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.Immutable;

@AutoValue
@Immutable
abstract class StressTestRunner {
  abstract ImmutableList<Operation> getOperations();

  abstract AbstractInstrument getInstrument();

  abstract int getCollectionIntervalMs();

  final void run() {
    List<Operation> operations = getOperations();
    int numThreads = operations.size();
    final CountDownLatch countDownLatch = new CountDownLatch(numThreads);
    Thread collectionThread =
        new Thread(
            () -> {
              // While workers still work, do collections.
              while (countDownLatch.getCount() != 0) {
                Uninterruptibles.sleepUninterruptibly(
                    getCollectionIntervalMs(), TimeUnit.MILLISECONDS);
              }
            });
    List<Thread> operationThreads = new ArrayList<>(numThreads);
    for (final Operation operation : operations) {
      operationThreads.add(
          new Thread(
              () -> {
                for (int i = 0; i < operation.getNumOperations(); i++) {
                  operation.getUpdater().update();
                  Uninterruptibles.sleepUninterruptibly(
                      operation.getOperationDelayMs(), TimeUnit.MILLISECONDS);
                }
                countDownLatch.countDown();
              }));
    }

    // Start collection thread then the rest of the worker threads.
    collectionThread.start();
    for (Thread thread : operationThreads) {
      thread.start();
    }

    // Wait for all the thread to finish.
    for (Thread thread : operationThreads) {
      Uninterruptibles.joinUninterruptibly(thread);
    }
    Uninterruptibles.joinUninterruptibly(collectionThread);
  }

  static Builder builder() {
    return new AutoValue_StressTestRunner.Builder();
  }

  @AutoValue.Builder
  abstract static class Builder {
    // TODO: Change this to MeterSdk when collect is available for the entire Meter.
    abstract Builder setInstrument(AbstractInstrument meterSdk);

    abstract ImmutableList.Builder<Operation> operationsBuilder();

    abstract Builder setCollectionIntervalMs(int collectionInterval);

    Builder addOperation(final Operation operation) {
      operationsBuilder().add(operation);
      return this;
    }

    public abstract StressTestRunner build();
  }

  @AutoValue
  @Immutable
  abstract static class Operation {

    abstract int getNumOperations();

    abstract int getOperationDelayMs();

    abstract OperationUpdater getUpdater();

    static Operation create(int numOperations, int operationDelayMs, OperationUpdater updater) {
      return new AutoValue_StressTestRunner_Operation(numOperations, operationDelayMs, updater);
    }
  }

  abstract static class OperationUpdater {

    /** Called every operation. */
    abstract void update();

    /** Called after all operations are completed. */
    abstract void cleanup();
  }

  StressTestRunner() {}
}
