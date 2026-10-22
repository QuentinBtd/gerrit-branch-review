// Copyright (C) 2026 The branch-review Authors
// SPDX-License-Identifier: Apache-2.0

package io.github.quentinbtd.gerrit.branchreview;

import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.logging.Metadata;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** Low-cardinality plugin metrics. Field values are fixed enums, never project or ref names. */
@Singleton
public class Metrics {
  private final Counter1<String> syncResults;
  private final Counter1<String> cleanupResults;
  private final AtomicInteger retryBacklog = new AtomicInteger();

  @Inject
  Metrics(MetricMaker metrics) {
    syncResults =
        metrics.newCounter(
            "sync_results",
            new Description("Branch review synchronizations by result").setRate().setUnit("syncs"),
            Field.ofString("result", Metadata.Builder::eventType)
                .description("created, updated, noop, not_synchronized, failed, ...")
                .build());
    cleanupResults =
        metrics.newCounter(
            "cleanup_results",
            new Description("Source branch cleanups after submit by result")
                .setRate()
                .setUnit("cleanups"),
            Field.ofString("result", Metadata.Builder::eventType)
                .description("deleted, skipped, failed")
                .build());
    metrics.newCallbackMetric(
        "retry_backlog",
        Integer.class,
        new Description("Lifecycles waiting for a synchronization or cleanup retry").setGauge(),
        retryBacklog::get);
  }

  public void sync(String result) {
    syncResults.increment(result);
  }

  public void cleanup(String result) {
    cleanupResults.increment(result);
  }

  public void setRetryBacklog(int n) {
    retryBacklog.set(n);
  }
}
