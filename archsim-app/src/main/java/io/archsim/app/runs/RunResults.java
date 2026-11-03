package io.archsim.app.runs;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A run with what it simulated and what came out: for a feature that keeps a copy of
 * them, as share links do.
 *
 * @param facts the run
 * @param report its report, once it succeeded
 * @param inputDocument the design it simulated, as it was queued
 */
public record RunResults(RunFacts facts, @Nullable JsonNode report, JsonNode inputDocument) {

}
