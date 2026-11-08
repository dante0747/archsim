/**
 * Parity with the frozen Python engine, for tests (ADR-0028): reading its golden files
 * ({@link io.archsim.parity.GoldenFile}), their entries
 * ({@link io.archsim.parity.GoldenEntry}), and comparing JSON by the port's bar
 * ({@link io.archsim.parity.JsonDiff}), and the rule that keeps a report the same on
 * every platform ({@link io.archsim.parity.StrictMathRule}). Shared with the modules of
 * later stages as Gradle test fixtures.
 */
@NullMarked
package io.archsim.parity;

import org.jspecify.annotations.NullMarked;
