/**
 * Prometheus range queries over HTTP, as the Python engine's {@code HttpPrometheus} made
 * them: each pinned to the address it checks, under one deadline and a size cap, failing
 * in the Python engine's words (ADR-0046), for the app and the CLI (ADR-0057). The
 * engine's only HTTP client.
 */
@NullMarked
package io.archsim.engine.calibration.prometheus.http;

import org.jspecify.annotations.NullMarked;
