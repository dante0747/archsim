package io.archsim.app.worker;

import java.util.Objects;
import java.util.stream.StreamSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;

/**
 * How a run ended, in the shapes the API reads and the Python worker wrote: a report with
 * its summary and digests, or an error's message, with the design's issues when it was
 * invalid.
 */
final class RunOutcomes {

	/** The report's figures a run's summary copies. */
	private static final String[] FIGURES = { "offeredRps", "throughputRps", "successRate", "timeoutRate",
			"errorRate" };

	/** The latency percentiles a run's summary copies. */
	private static final String[] PERCENTILES = { "p50", "p95", "p99" };

	private static final String MESSAGE = "message";

	private final JsonMapper json;

	RunOutcomes(JsonMapper json) {
		this.json = json;
	}

	/**
	 * A run that simulated: its report, the summary the run lists show, its digests and
	 * the time it took, from its provenance.
	 * @param report the report
	 * @return the outcome
	 */
	RunOutcome succeeded(JsonNode report) {
		JsonNode provenance = report.path("provenance");
		return new RunOutcome.Succeeded(report, summary(report), provenance.path("inputDigest").stringValue(),
				provenance.path("resultDigest").stringValue(), provenance.path("wallSeconds").doubleValue());
	}

	/**
	 * A run whose simulation failed: an invalid design's first error with its issues, a
	 * limit's words, or anything else as an internal error, named by its type.
	 * @param failure what the simulation threw
	 * @return the outcome
	 */
	RunOutcome failed(Throwable failure) {
		return switch (failure) {
			case DocumentInvalidException invalid -> invalid(invalid);
			case SimulationLimitException limit -> error(limit.getMessage());
			default -> error("Internal error while simulating: " + failure.getClass().getSimpleName() + ": "
					+ Objects.requireNonNullElse(failure.getMessage(), ""));
		};
	}

	/**
	 * A run its worker stopped waiting for, past its hard deadline.
	 * @param maxWallSeconds the run's wall-clock limit
	 * @return the outcome
	 */
	RunOutcome overran(double maxWallSeconds) {
		return error("Simulation exceeded the wall-clock limit of " + PythonFormat.format(maxWallSeconds, ".0f")
				+ " s and was stopped. Reduce RPS, duration, fan-out or model size.");
	}

	private RunOutcome invalid(DocumentInvalidException invalid) {
		ObjectNode error = this.json.createObjectNode().put(MESSAGE, invalid.getMessage());
		ArrayNode issues = error.putArray("issues");
		invalid.issues().stream().map(Issue::toJson).forEach(issues::add);
		return new RunOutcome.Failed(error);
	}

	private RunOutcome error(String message) {
		return new RunOutcome.Failed(this.json.createObjectNode().put(MESSAGE, message));
	}

	// the figures the run lists show, and the first bottleneck the run found
	private ObjectNode summary(JsonNode report) {
		ObjectNode summary = this.json.createObjectNode();
		JsonNode figures = report.path("summary");
		for (String figure : FIGURES) {
			summary.set(figure, figures.get(figure));
		}
		for (String percentile : PERCENTILES) {
			summary.set(percentile, figures.path("latencyMs").get(percentile));
		}
		JsonNode bottleneck = StreamSupport.stream(report.path("findings").spliterator(), false)
			.filter((finding) -> "bottleneck".equals(finding.path("kind").stringValue(null)))
			.findFirst()
			.orElse(null);
		summary.set("bottleneck", (bottleneck != null) ? bottleneck.get("title") : null);
		summary.set("bottleneckNodeId", (bottleneck != null) ? bottleneck.get("nodeId") : null);
		return summary;
	}

}
