package io.archsim.model.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Scenario;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.GoldenFile;
import io.archsim.parity.JsonDiff;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What scenario resolution says about the invalid fixtures: an unknown scenario, an override of a
 * missing node or path, and a design the overrides make invalid fail as in the Python engine, and
 * the rest resolve.
 *
 * <p>Python's {@code resolve_scenario_sized} also pins the price snapshot and fills in auto sizes
 * between normalising and applying overrides; neither can fail, and both are the engine's (stage
 * 3), so this checks the model's part of it, composed in the same order.
 */
class ScenarioResolutionTest {

    private static final ComponentRegistry REGISTRY = StandardComponents.registry();

    @TestFactory
    Stream<DynamicTest> resolution_fails_where_the_python_engine_failed_and_only_there() {
        GoldenFile file = GoldenFile.read("fixtures/invalid-documents");
        List<GoldenEntry> entries = file.entries("archsim_engine.scenarios.resolve_scenario_sized");
        assertThat(entries).isNotEmpty();
        return entries.stream().map(entry -> DynamicTest.dynamicTest(entry.pointer(), () -> check(entry)));
    }

    private static void check(GoldenEntry entry) {
        ArchitectureDocument document = DesignDocuments.parse(entry.input("doc"));
        JsonNode id = entry.input("scenarioId");
        String scenarioId = id.isNull() ? null : id.stringValue();
        if (!entry.raised()) {
            assertThatCode(() -> resolve(document, scenarioId)).doesNotThrowAnyException();
            return;
        }
        DocumentInvalidException invalid =
                catchThrowableOfType(DocumentInvalidException.class, () -> resolve(document, scenarioId));
        assertThat(JsonDiff.differences(entry.expectedError(), error(invalid)))
                .as("%s: the error differs from the Python engine's at", entry)
                .isEmpty();
    }

    /** Python's resolution, but for the prices and sizes the engine fills in. */
    private static ArchitectureDocument resolve(ArchitectureDocument document, @Nullable String scenarioId) {
        ObjectNode base = DesignDocuments.normalize(document, REGISTRY);
        if (scenarioId != null) {
            Scenario scenario = Scenarios.find(document, scenarioId);
            base = Scenarios.applyOverrides(base, scenario.overrides(), "Scenario '%s'".formatted(scenario.name()));
            Scenarios.dropStaleInstanceTypes(base, document, scenario);
        }
        base.putArray("scenarios");
        base.putObject("layout").putObject("nodes");
        return DesignDocuments.parse(base);
    }

    private static JsonNode error(DocumentInvalidException invalid) {
        ObjectNode json = JsonNodeFactory.instance
                .objectNode()
                .put("type", "DocumentInvalidError")
                .put("message", invalid.getMessage());
        invalid.issues().stream().map(Issue::toJson).forEach(json.putArray("issues")::add);
        return json;
    }
}
