package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.FailureCause;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Fault;
import io.archsim.model.document.FaultKind;
import io.archsim.model.document.NodeSpec;

/**
 * What each injected fault did: success and p95 latency inside its window against before
 * it (or after it, for a fault that starts in the warm-up), how long the design took to
 * recover, and, for an outage, what its part refused.
 */
final class FaultFindings {

	/** A drop in success larger than this is named. */
	private static final double DROP = 0.005;

	/** A p95 rise above this times the baseline is named in the title. */
	private static final double LATENCY_ROSE = 1.2;

	/** Recovered: success within this of the baseline... */
	private static final double RECOVERED_SHARE = 0.01;

	/** ... and p95 within this times it. */
	private static final double RECOVERED_LATENCY = 1.25;

	/**
	 * Under this success inside the window, or above {@link #CRITICAL_LATENCY}, critical.
	 */
	private static final double CRITICAL_SHARE = 0.95;

	private static final double CRITICAL_LATENCY = 3;

	/** Under this success, or above {@link #WARNING_LATENCY}, a warning. */
	private static final double WARNING_SHARE = 0.99;

	private static final double WARNING_LATENCY = 1.5;

	private static final String RELEASE_WORKERS = "Set timeouts below the client timeout and add a circuit breaker so "
			+ "slow calls release workers quickly; keep capacity headroom for degraded periods.";

	private static final String BUDGET_LATENCY = "Budget timeouts for the extra latency, retry on another "
			+ "instance, or cut the number of sequential calls across this link.";

	private static final String FALL_BACK_FROM_CACHE = "Treat cache errors as misses and fall back to the database: "
			+ "turn on 'Carry on if this call fails' on the link to the cache and set the link to the database to "
			+ "'Reads only on a cache miss' (check the database can take every read), or run a replicated cache.";

	private static final String FAIL_OVER = "Add a replica with automatic failover, and fail fast "
			+ "(timeouts, circuit breaker) so callers do not pile up while it is down.";

	private static final String RUN_REDUNDANT = "Run redundant instances behind a load balancer and retry on "
			+ "another instance, so losing one component does not stop traffic.";

	private static final String ADD_FALLBACK = "Add a fallback (or queue the work for later), with a timeout "
			+ "and a circuit breaker so the failure stays contained.";

	private final RunReport report;

	private final ArchitectureDocument resolved;

	private final ClientSeries series;

	private final Map<String, String> labels;

	private FaultFindings(RunReport report, ArchitectureDocument resolved, ClientSeries series) {
		this.report = report;
		this.resolved = resolved;
		this.series = series;
		this.labels = resolved.architecture()
			.nodes()
			.stream()
			.collect(Collectors.toMap(NodeSpec::id, (node) -> node.label().isEmpty() ? node.id() : node.label(),
					(first, second) -> second));
	}

	/**
	 * The findings of a run's faults.
	 * @param run the run
	 * @param series the run's client-side time series
	 * @return a finding for each fault that starts before the offered load ends and has
	 * requests that finished inside its window
	 */
	static List<RunFinding> of(RunContext run, ClientSeries series) {
		FaultFindings faults = new FaultFindings(run.report(), run.resolved(), series);
		List<RunFinding> findings = new ArrayList<>();
		for (Fault fault : run.resolved().workload().faults()) {
			faults.finding(fault).ifPresent(findings::add);
		}
		return findings;
	}

	private Optional<RunFinding> finding(Fault fault) {
		double duration = this.report.window().durationS();
		if (fault.startS() >= duration) {
			return Optional.empty();
		}
		Impact impact = Impact.of(fault, Math.min(fault.startS() + fault.durationS(), duration), this.series);
		if (impact.during() == null) {
			return Optional.empty();
		}
		FindingSeverity severity = impact.severity();
		List<String> parts = new ArrayList<>(List.of(impact.description()));
		impact.recovery(this.series.bucket()).ifPresent(parts::add);
		ObjectNode evidence = impact.evidence(fault);
		if (fault.kind() == FaultKind.OUTAGE) {
			outage(fault).ifPresent((outage) -> {
				parts.add(outage.text());
				evidence.put("refusedCalls", outage.refused()).put("failedAtTarget", outage.failed());
			});
		}
		return Optional.of(new RunFinding(severity, FindingKind.FAULT_IMPACT,
				(fault.kind() != FaultKind.LATENCY) ? fault.target() : null, impact.title(subject(fault)),
				String.join(" ", parts), (severity != FindingSeverity.OK) ? suggestion(fault) : null, evidence));
	}

	// What the fault did, in words, such as: outage of DB.
	private String subject(Fault fault) {
		Function<String, String> label = (id) -> this.labels.getOrDefault(id, id);
		return switch (fault.kind()) {
			case LATENCY -> "+" + FindingText.general(fault.extraMs()) + " ms latency on "
					+ this.resolved.architecture()
						.edges()
						.stream()
						.filter((edge) -> edge.id().equals(fault.target()))
						.findFirst()
						.map((edge) -> label.apply(edge.source()) + " → " + label.apply(edge.target()))
						.orElse(fault.target());
			case SLOWDOWN -> FindingText.general(fault.factor()) + "x slowdown of " + label.apply(fault.target());
			case OUTAGE -> "outage of " + label.apply(fault.target());
		};
	}

	// What an outage did at its part: the calls it refused while down, and the client
	// requests that failed there rather than carrying on without it.
	private Optional<Outage> outage(Fault fault) {
		Optional<ReportNode> node = this.report.node(fault.target());
		if (node.isEmpty()) {
			return Optional.empty();
		}
		long refused = PartLoad.outageRefusals(node.get().summary());
		long failed = this.report.summary()
			.failureBreakdown()
			.stream()
			.filter((cause) -> FailureReason.OUTAGE.is(cause.reason()) && fault.target().equals(cause.nodeId()))
			.mapToLong(FailureCause::count)
			.sum();
		String name = this.labels.getOrDefault(fault.target(), fault.target());
		String text;
		if (refused == 0) {
			text = name + " received no calls while it was down.";
		}
		else {
			text = name + " refused " + FindingText.count(refused) + " calls while it was down"
					+ ((failed != 0) ? "; " + FindingText.count(failed) + " client requests failed because of it."
							: "; no client request failed there: its callers carried on without it.");
		}
		return Optional.of(new Outage(text, refused, failed));
	}

	private String suggestion(Fault fault) {
		return switch (fault.kind()) {
			case SLOWDOWN -> RELEASE_WORKERS;
			case LATENCY -> BUDGET_LATENCY;
			case OUTAGE -> outageSuggestion(this.resolved.architecture()
				.nodes()
				.stream()
				.filter((node) -> node.id().equals(fault.target()))
				.findFirst()
				.map(NodeSpec::type)
				.orElse(""));
		};
	}

	private static String outageSuggestion(String type) {
		return switch (type) {
			case CacheSpec.TYPE -> FALL_BACK_FROM_CACHE;
			case DatabaseSpec.TYPE -> FAIL_OVER;
			case ServiceSpec.TYPE, LoadBalancerSpec.TYPE -> RUN_REDUNDANT;
			default -> ADD_FALLBACK;
		};
	}

	/**
	 * What an outage did at its part.
	 *
	 * @param text what it did, in words
	 * @param refused the calls its part refused while down
	 * @param failed the client requests that failed there
	 */
	private record Outage(String text, long refused, long failed) {

	}

	/**
	 * Success and p95 latency inside a fault's window, against its baseline, and when the
	 * design recovered.
	 *
	 * @param start when the fault started, in seconds
	 * @param end when it ended, or the offered load did, in seconds
	 * @param before the baseline's success, if any request finished then
	 * @param during the success inside the window, if any request finished then
	 * @param p95Before the baseline's mean p95 latency, in milliseconds
	 * @param p95During the window's mean p95 latency, in milliseconds
	 * @param recovered whether the design looked like its baseline again before the
	 * offered load ended
	 * @param recoveryS how long after the fault it recovered, in seconds, if it was
	 * affected and did
	 */
	private record Impact(double start, double end, @Nullable Double before, @Nullable Double during,
			@Nullable Double p95Before, @Nullable Double p95During, boolean recovered, @Nullable Double recoveryS) {

		static Impact of(Fault fault, double end, ClientSeries series) {
			int from = series.index(fault.startS());
			int to = Math.max(from + 1, series.indexAfter(end));
			int baseFrom = series.firstMeasured();
			int baseTo = from;
			if (baseTo - baseFrom < 1) {
				// a fault that starts during the warm-up: compare with after it instead
				baseFrom = to;
				baseTo = series.end();
			}
			Double before = series.share(baseFrom, baseTo);
			Double p95Before = series.p95(baseFrom, baseTo);
			Impact impact = new Impact(fault.startS(), end, before, series.share(from, to), p95Before,
					series.p95(from, to), true, null);
			if (before == null || !impact.affected()) {
				return impact;
			}
			for (int j = to; j < series.end(); j++) {
				Double share = series.share(j, j + 1);
				Double p95 = series.p95(j, j + 1);
				boolean latencyBack = p95Before == null || p95 == null || p95 <= p95Before * RECOVERED_LATENCY;
				if (share != null && share >= before - RECOVERED_SHARE && latencyBack) {
					return impact.recoveredAfter(Math.max(0.0, series.start(j) - end));
				}
			}
			return new Impact(fault.startS(), end, before, impact.during(), p95Before, impact.p95During(), false, null);
		}

		private Impact recoveredAfter(double seconds) {
			return new Impact(this.start, this.end, this.before, this.during, this.p95Before, this.p95During, true,
					seconds);
		}

		// How far success fell inside the window; nothing without a baseline.
		double drop() {
			return (this.before != null && this.during != null) ? this.before - this.during : 0.0;
		}

		// The window's p95 over the baseline's, if both are there and not zero.
		@Nullable Double ratio() {
			Double inside = this.p95During;
			Double baseline = this.p95Before;
			if (inside == null || baseline == null || inside == 0 || baseline == 0) {
				return null;
			}
			return inside / baseline;
		}

		private boolean affected() {
			Double ratio = ratio();
			return drop() > DROP || ((ratio != null) ? ratio : 1) > RECOVERED_LATENCY;
		}

		FindingSeverity severity() {
			double inside = (this.during != null) ? this.during : 0.0;
			double ratio = (ratio() != null) ? ratio() : 0.0;
			if (inside < CRITICAL_SHARE || ratio > CRITICAL_LATENCY) {
				return FindingSeverity.CRITICAL;
			}
			if (inside < WARNING_SHARE || drop() > DROP || ratio > WARNING_LATENCY) {
				return FindingSeverity.WARNING;
			}
			return FindingSeverity.OK;
		}

		String title(String subject) {
			String window = FindingText.general(this.start) + "-" + FindingText.general(this.end) + " s";
			Double ratio = ratio();
			if (this.before != null && drop() > DROP) {
				return "During the " + subject + " (" + window + ") success fell from " + FindingText.share(this.before)
						+ " to " + FindingText.share(this.during);
			}
			if (ratio != null && ratio > LATENCY_ROSE) {
				return "During the " + subject + " (" + window + ") p95 latency rose from "
						+ FindingText.ms(this.p95Before) + " to " + FindingText.ms(this.p95During);
			}
			return "The system absorbed the " + subject + " (" + window + ")";
		}

		String description() {
			return "Inside the fault window " + FindingText.share(this.during) + " of requests succeeded (before: "
					+ FindingText.share(this.before) + "); p95 latency averaged " + FindingText.ms(this.p95During)
					+ " (before: " + FindingText.ms(this.p95Before) + ").";
		}

		Optional<String> recovery(double bucket) {
			if (!this.recovered) {
				return Optional.of("It had not recovered by the time arrivals stopped.");
			}
			if (this.recoveryS == null) {
				return Optional.empty();
			}
			return Optional.of((this.recoveryS < bucket) ? "It recovered as soon as the fault ended."
					: "It took about " + FindingText.general(this.recoveryS) + " s after the fault ended to recover.");
		}

		ObjectNode evidence(Fault fault) {
			return RunFinding.emptyEvidence()
				.put("faultId", fault.id())
				.put("faultKind", fault.kind().json())
				.put("target", fault.target())
				.put("startS", this.start)
				.put("endS", this.end)
				.put("successBefore", this.before)
				.put("successDuring", this.during)
				.put("p95BeforeMs", this.p95Before)
				.put("p95DuringMs", this.p95During)
				.put("recoveryS", this.recoveryS)
				.put("recovered", this.recovered);
		}

	}

}
