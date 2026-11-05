package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.report.FailureCause;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.RunSummary;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.client.ClientConfig;

/** What a run's clients saw: requests that did not complete, why they failed, retries. */
final class ClientFindings {

	/** Under this share of the offered load completed, clients notice. */
	private static final double KEPT_UP = 0.97;

	/** Under this share, they notice badly. */
	private static final double FELL_BEHIND = 0.8;

	/** Above this many attempts per request, retries are named. */
	private static final double AMPLIFIED = 1.05;

	/** The causes of failure a finding cites. */
	private static final int CITED_CAUSES = 5;

	private ClientFindings() {
	}

	/**
	 * What a run's clients saw.
	 * @param run the run, for its figures and its clients' timeouts
	 * @return a throughput gap, the main cause of failed requests and retries, as there
	 * are
	 */
	static List<RunFinding> of(RunContext run) {
		RunSummary summary = run.report().summary();
		List<RunFinding> findings = new ArrayList<>();
		throughputGap(summary).ifPresent(findings::add);
		if (!summary.failureBreakdown().isEmpty()) {
			findings.add(failures(summary, run));
		}
		Double amplification = summary.retryAmplification();
		if (amplification != null && amplification > AMPLIFIED) {
			findings.add(new RunFinding(FindingSeverity.WARNING, FindingKind.RETRIES, null,
					"Client retries amplified load by " + FindingText.hundredths(amplification) + "x",
					FindingText.count(summary.attempts()) + " attempts were sent for "
							+ FindingText.count(summary.arrived())
							+ " user requests. Retries add load exactly when the system is struggling.",
					null, RunFinding.emptyEvidence().put("retryAmplification", amplification)));
		}
		return findings;
	}

	private static Optional<RunFinding> throughputGap(RunSummary summary) {
		double offered = Objects.requireNonNullElse(summary.offeredRps(), 0.0);
		double throughput = Objects.requireNonNullElse(summary.throughputRps(), 0.0);
		if (offered <= 0 || throughput >= KEPT_UP * offered) {
			return Optional.empty();
		}
		return Optional.of(new RunFinding(
				(throughput < FELL_BEHIND * offered) ? FindingSeverity.CRITICAL : FindingSeverity.WARNING,
				FindingKind.THROUGHPUT_GAP, null,
				"Only " + FindingText.wholeWithSeparators(throughput) + " of "
						+ FindingText.wholeWithSeparators(offered) + " req/s completed successfully",
				"Success rate " + FindingText.percentToTenths(Objects.requireNonNullElse(summary.successRate(), 0.0))
						+ "; " + FindingText.count(summary.failed()) + " requests failed and "
						+ FindingText.count(summary.timedOut()) + " timed out in the measurement window.",
				null, RunFinding.emptyEvidence().put("offeredRps", offered).put("throughputRps", throughput)));
	}

	// The main cause of failed requests: the first of the run's breakdown.
	private static RunFinding failures(RunSummary summary, RunContext run) {
		FailureCause top = summary.failureBreakdown().getFirst();
		ArrayNode cited = JsonNodeFactory.instance.arrayNode();
		summary.failureBreakdown().stream().limit(CITED_CAUSES).forEach((cause) -> cited.add(RecordCodec.write(cause)));
		String share = FindingText.percent(Objects.requireNonNullElse(top.share(), 0.0));
		return new RunFinding(FindingSeverity.WARNING, FindingKind.FAILURES, top.nodeId(),
				"Main cause of failed requests", cause(top, run) + " (" + share + " of all failures).", null,
				RunFinding.emptyEvidence().set("breakdown", cited));
	}

	private static String cause(FailureCause top, RunContext run) {
		String count = FindingText.count(top.count());
		String where = (top.nodeLabel() != null && !top.nodeLabel().isEmpty()) ? top.nodeLabel() : "the system";
		if (FailureReason.CLIENT_TIMEOUT.is(top.reason())) {
			// the client group that gave up: groups can have different timeouts
			double timeout = clientTimeout(top.nodeId(), run);
			return (timeout != 0)
					? count + " requests exceeded the " + FindingText.general(timeout) + " ms client timeout"
					: count + " requests hit the client timeout";
		}
		if (FailureReason.DEPENDENCY_TIMEOUT.is(top.reason())) {
			return count + " requests failed at " + where + ": it " + FindingText.orNone(top.reasonText()) + " (link "
					+ FindingText.orNone(top.edgeId()) + ")";
		}
		return count + " requests failed at " + where + ": " + FindingText.orNone(top.reasonText());
	}

	// A client group's timeout; zero for a part that is not a client group.
	private static double clientTimeout(@Nullable String nodeId, RunContext run) {
		if (nodeId == null) {
			return 0.0;
		}
		return run.config(nodeId, ClientConfig.class).map(ClientConfig::timeoutMs).orElse(0.0);
	}

}
