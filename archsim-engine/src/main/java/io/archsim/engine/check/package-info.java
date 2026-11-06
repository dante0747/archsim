/**
 * The SLO gate for CI, {@code archsim check}: what a check measures of a run, the
 * regressions against a baseline, whether the check passes, and its headline and
 * pull-request comment. The command itself is stage 5's; it measures with the simulator,
 * and sweeps the breaking points (stage 4).
 */
@NullMarked
package io.archsim.engine.check;

import org.jspecify.annotations.NullMarked;
