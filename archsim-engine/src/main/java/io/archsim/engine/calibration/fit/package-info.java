/**
 * Measured latencies fitted to ArchSim's distributions, as the Python engine's
 * {@code calibration/fit.py} fits them: a Prometheus histogram's quantiles, interpolated
 * inside each bucket as {@code histogram_quantile} gives them, or samples' quantiles,
 * fitted to a lognormal or kept as an empirical table, with the fit's error.
 */
@NullMarked
package io.archsim.engine.calibration.fit;

import org.jspecify.annotations.NullMarked;
