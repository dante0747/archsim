/**
 * The design review: an architecture linter over a design, static, with no simulation. It
 * flags the resilience risks reviewers look for (single points of failure, missing
 * timeouts, stacked retries, timeout inversion, pool exhaustion, unbounded queues and
 * more) with the design's own numbers, word for word as the Python engine wrote them.
 */
@NullMarked
package io.archsim.engine.review;

import org.jspecify.annotations.NullMarked;
