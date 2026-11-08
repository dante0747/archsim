package io.archsim.engine.reporting;

import java.util.List;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.ReportDigest;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.ArchitectureDocument;

/**
 * A report's {@code provenance}: exactly what was simulated (the versions, the seed, the
 * scenario and its overrides, the digest of the design simulated), the digest of the
 * result, the work it took, and the runtime that ran it.
 */
final class Provenance {

	/** The figures the result's digest covers. */
	private static final List<String> RESULT = List.of("summary", "timeseries", "nodes", "edges", "window");

	private static final int WALL_DIGITS = 3;

	private Provenance() {
	}

	/**
	 * The provenance of a run.
	 * @param run the finished run
	 * @param report the report, with the parts' status
	 * @param input the design simulated, as the report gives it
	 * @param wallSeconds the wall-clock time the run took, in seconds
	 * @return the provenance
	 */
	static ObjectNode write(SimulatedRun run, ObjectNode report, ObjectNode input, double wallSeconds) {
		PreparedRun prepared = run.prepared();
		ObjectNode provenance = Figures.NODES.objectNode();
		provenance.put("engineVersion", EngineVersions.ENGINE);
		provenance.put("schemaVersion", ArchitectureDocument.SCHEMA_VERSION);
		provenance.put("reportVersion", EngineVersions.REPORT);
		provenance.put("seed", prepared.resolved().workload().seed());
		provenance.put("scenarioId", prepared.scenarioId());
		provenance.put("scenarioName", prepared.scenarioName());
		ArrayNode overrides = provenance.putArray("overrides");
		prepared.overrides().forEach((override) -> overrides.add(RecordCodec.write(override)));
		provenance.put("inputDigest", ReportDigest.of(input));
		ObjectNode result = Figures.NODES.objectNode();
		RESULT.forEach((part) -> result.set(part, report.get(part)));
		provenance.put("resultDigest", ReportDigest.of(result));
		provenance.put("requestsGenerated", run.clients().offered().total());
		provenance.put("eventsProcessed", run.events());
		provenance.put("wallSeconds", PythonRound.round(wallSeconds, WALL_DIGITS));
		provenance.put("java", System.getProperty("java.version"));
		return provenance;
	}

}
