package io.archsim.app.runs;

/**
 * A run to queue: what it simulates, where, and how.
 *
 * @param destination its project and workspace, and its limits
 * @param prepared what the engine prepared: the design to store, its scenario and seed
 * @param label what to call it
 * @param mode {@code full} or {@code sampled}
 */
record NewRun(Destination destination, PlannedRun prepared, String label, RunMode mode) {

}
