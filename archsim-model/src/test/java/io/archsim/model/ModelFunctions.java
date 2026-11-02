package io.archsim.model;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Empirical;
import io.archsim.model.upgrade.UpgradeException;
import io.archsim.model.upgrade.Upgraded;
import io.archsim.model.upgrade.Upgrades;
import io.archsim.model.validation.DocumentInvalidException;
import io.archsim.model.validation.Issue;
import io.archsim.parity.GoldenEntry;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Python engine's functions this module ports, each as the Java call that replaces it, taking
 * a golden entry's input and giving its output as the golden file records it.
 */
final class ModelFunctions {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.of(
            "archsim_engine.migrations.migrate_document",
            ModelFunctions::migrateDocument,
            "archsim_engine.scenarios.load_document",
            ModelFunctions::loadDocument,
            "archsim_engine.scenarios.simulation_input",
            ModelFunctions::simulationInput,
            "archsim_engine.distributions.Distribution.expected",
            entry -> NODES.numberNode(distribution(entry).expected()),
            "archsim_engine.distributions.Distribution.describe",
            entry -> NODES.stringNode(distribution(entry).describe()),
            "archsim_engine.distributions.Empirical.quantile",
            entry -> NODES.numberNode(
                    ((Empirical) distribution(entry)).quantile(entry.input("q").doubleValue())));

    private ModelFunctions() {}

    /** The Python functions ported here, by qualified name. */
    static Set<String> names() {
        return PORTED.keySet();
    }

    /** The Java counterpart of the entry's function on the entry's input. */
    static JsonNode call(GoldenEntry entry) {
        return Objects.requireNonNull(PORTED.get(entry.fn()), entry.fn()).apply(entry);
    }

    /** How the Python engine records an exception: its type, its message, and a document's issues. */
    static JsonNode error(RuntimeException e) {
        ObjectNode json = NODES.objectNode();
        switch (e) {
            case UpgradeException upgrade -> json.put("type", "MigrationError").put("message", upgrade.getMessage());
            case DocumentInvalidException invalid -> {
                json.put("type", "DocumentInvalidError").put("message", invalid.getMessage());
                invalid.issues().stream().map(Issue::toJson).forEach(json.putArray("issues")::add);
            }
            default -> throw e;
        }
        return json;
    }

    private static JsonNode migrateDocument(GoldenEntry entry) {
        Upgraded upgraded = Upgrades.toCurrent(entry.input("raw"));
        ObjectNode output = NODES.objectNode();
        output.set("document", upgraded.document());
        upgraded.notes().forEach(output.putArray("notes")::add);
        return output;
    }

    private static JsonNode loadDocument(GoldenEntry entry) {
        return DesignDocuments.toJson(DesignDocuments.load(entry.input("raw")));
    }

    private static JsonNode simulationInput(GoldenEntry entry) {
        return DesignDocuments.simulationInput(DesignDocuments.parse(entry.input("resolved")));
    }

    private static Distribution distribution(GoldenEntry entry) {
        return RecordCodec.read(entry.input("d"), Distribution.class);
    }
}
