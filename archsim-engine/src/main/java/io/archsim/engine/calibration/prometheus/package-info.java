/**
 * What Prometheus range queries return, read as the Python engine's
 * {@code calibration/prometheus.py} reads it: a histogram from {@code *_bucket} series
 * averaged over the range, one figure from a series, or series added up per timestamp.
 */
@NullMarked
package io.archsim.engine.calibration.prometheus;

import org.jspecify.annotations.NullMarked;
