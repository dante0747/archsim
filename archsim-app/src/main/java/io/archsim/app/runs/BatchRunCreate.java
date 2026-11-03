package io.archsim.app.runs;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MinItems;

/**
 * What {@code POST /projects/{id}/runs/batch} takes: several scenarios of one design.
 *
 * @param scenarioIds the scenarios, {@code null} for the design itself
 * @param document simulate this design instead of the saved one
 */
record BatchRunCreate(@MinItems(1) @MaxItems(10) List<@Nullable String> scenarioIds, @Nullable ObjectNode document) {

}
