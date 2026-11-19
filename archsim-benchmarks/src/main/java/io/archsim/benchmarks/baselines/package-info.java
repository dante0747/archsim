/**
 * Simple estimates of a kept run's knee from its calibration alone, to set beside the
 * simulator's prediction ({@code baselines}, ADR-0087): bottleneck arithmetic on the
 * calibrated inputs, the engine's own analytic estimate, and a straight line through the
 * staircase's measured CPU, extrapolated to each container's CPUs.
 */
@NullMarked
package io.archsim.benchmarks.baselines;

import org.jspecify.annotations.NullMarked;
