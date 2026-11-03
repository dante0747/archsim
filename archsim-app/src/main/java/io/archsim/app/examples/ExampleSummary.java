package io.archsim.app.examples;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An example design as the template gallery lists it.
 *
 * @param id the example's id, which {@code GET /examples/{id}} and new projects name
 * @param name its name, or its id when it has none
 * @param description what it shows, if it says
 * @param tags its tags
 * @param scenarioCount how many scenarios it has
 */
record ExampleSummary(String id, String name, @Nullable String description, List<String> tags, int scenarioCount) {

}
