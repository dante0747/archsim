package io.archsim.model.document;

import io.archsim.model.codec.Const;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxItems;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An ArchSim design: the one JSON document the editor saves, the API stores and the engine reads
 * (ADR-0002). Its parts are kept apart on purpose: the topology, the traffic, replayable
 * scenarios, objectives, rendering, where inputs came from, the last calibration and prices.
 *
 * @param schemaVersion always the current {@value #SCHEMA_VERSION}; older documents are upgraded
 *     before they are read
 * @param kind always {@value #KIND}
 * @param metadata its name, description and tags
 * @param architecture the topology: nodes and the links between them
 * @param workload the traffic the clients send
 * @param scenarios named variants of the design
 * @param objectives the service-level objectives runs are judged against
 * @param layout how it is drawn (never changes a result)
 * @param provenance where inputs came from, by path: {@code nodes/<id>/<config key>}, {@code
 *     edges/<id>/<config key>} or {@code workload/<field>}
 * @param calibration the last calibration from telemetry
 * @param pricing where prices come from
 */
@Description("Top-level interchange document. Serialize with ``to_json_dict()``.")
public record ArchitectureDocument(
        @Default(SCHEMA_VERSION) @Const(SCHEMA_VERSION) String schemaVersion,
        @Default(KIND) @Const(KIND) String kind,
        @Default("{}") Metadata metadata,
        @Default("{}") Architecture architecture,
        @Default("{}") Workload workload,
        @Default("[]") @MaxItems(50) List<Scenario> scenarios,
        @Default("{}") Objectives objectives,
        @Default("{}") Layout layout,

        @Default("{}")
        @Description("Where inputs came from, keyed by path: nodes/<id>/<config key>, edges/<id>/<config key> or"
                + " workload/<field>.")
        Map<String, InputProvenance> provenance,

        @Nullable CalibrationRecord calibration,
        @Default("{}") Pricing pricing) {

    /** The schema version of documents this model reads and writes. */
    public static final String SCHEMA_VERSION = "1.7";

    /** What every design document's {@code kind} says. */
    public static final String KIND = "archsim.architecture";

    public ArchitectureDocument {
        scenarios = List.copyOf(scenarios);
        provenance = Collections.unmodifiableMap(new LinkedHashMap<>(provenance));
    }

    /** The first node with this id. */
    public Optional<NodeSpec> node(String id) {
        return architecture.node(id);
    }

    /** The first scenario with this id. */
    public Optional<Scenario> scenario(String id) {
        return scenarios.stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
