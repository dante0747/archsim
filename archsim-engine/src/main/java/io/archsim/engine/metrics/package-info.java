/**
 * What a run measures, in memory bounded by its time buckets and histogram bins, never by
 * its requests: counters, time-weighted gauges, log-bucketed latency histograms and
 * running means, as the Python engine's {@code metrics} module kept them.
 */
@NullMarked
package io.archsim.engine.metrics;

import org.jspecify.annotations.NullMarked;
