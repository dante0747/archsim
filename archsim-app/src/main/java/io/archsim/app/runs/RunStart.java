package io.archsim.app.runs;

import tools.jackson.databind.node.ObjectNode;

/**
 * A start checked and prepared, ready to be counted and queued: one run, or a seed
 * group's first.
 *
 * @param plan the workspace's limits
 * @param destination where the runs go
 * @param document the design they simulate
 * @param first the design prepared with its own seed
 */
record RunStart(RunPlanner.Plan plan, Destination destination, ObjectNode document, PlannedRun first) {

}
