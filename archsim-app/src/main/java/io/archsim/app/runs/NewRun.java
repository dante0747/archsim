package io.archsim.app.runs;

import io.archsim.app.engine.PreparedRun;

/**
 * A run to queue: what it simulates, where, and how.
 *
 * @param destination its project and workspace, and its limits
 * @param prepared what the engine prepared: the design to store, its scenario and seed
 * @param label what to call it
 * @param mode {@code full} or {@code sampled}
 */
record NewRun(Destination destination, PreparedRun prepared, String label, RunMode mode) {

}
