package io.archsim.model.validation;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.Fault;
import io.archsim.model.document.FaultKind;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Workload;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;

/**
 * Checks the workload: that each fault (a chaos experiment) targets a part of the kind it
 * applies to and happens inside the run, and that the workload offers some traffic.
 */
final class WorkloadChecks {

	private final ValidationContext context;

	private final Workload workload;

	WorkloadChecks(ValidationContext context) {
		this.context = context;
		this.workload = context.document().workload();
	}

	void check() {
		for (Fault fault : this.workload.faults()) {
			checkFault(fault);
		}
		if (this.workload.peakRate() == 0) {
			this.context.report(Issue
				.warning("workload.no_traffic",
						"The workload offers 0 requests/s, so the run will complete no requests.")
				.withHint("Set RPS above 0."));
		}
	}

	/**
	 * Reports a fault whose target is wrong; failing that, one that starts after the run
	 * or runs past it.
	 * @param fault the fault to check
	 */
	private void checkFault(Fault fault) {
		String what = describe(fault);
		String path = "workload.faults." + fault.id();
		NodeSpec node = this.context.node(fault.target());
		Optional<Issue> wrong = (fault.kind() == FaultKind.LATENCY) ? latencyTargetProblem(fault, what, path, node)
				: componentTargetProblem(fault, what, path, node);
		if (wrong.isPresent()) {
			this.context.report(wrong.get());
			return;
		}
		Optional<Issue> timing = timingProblem(fault, what);
		if (timing.isPresent()) {
			this.context.report((node != null) ? timing.get().atNode(node.id()).atPath(path)
					: timing.get().atEdge(fault.target()).atPath(path));
		}
	}

	/**
	 * How messages name a fault: its label, or its kind and id.
	 * @param fault the fault to name
	 * @return the fault's name
	 */
	private static String describe(Fault fault) {
		String label = fault.label();
		return (label != null && !label.isEmpty()) ? label : "%s fault '%s'".formatted(fault.kind().json(), fault.id());
	}

	/**
	 * What is wrong with a latency fault's target, which must be a link.
	 * @param fault the latency fault
	 * @param what how messages name the fault
	 * @param path where the fault is in the document
	 * @param node the node the fault targets, or {@code null} when it targets none
	 * @return the problem, or empty when the target is a link
	 */
	private Optional<Issue> latencyTargetProblem(Fault fault, String what, String path, @Nullable NodeSpec node) {
		if (this.context.hasEdge(fault.target())) {
			return Optional.empty();
		}
		if (node != null) {
			return Optional
				.of(Issue
					.error("fault_wrong_target",
							"%s targets component '%s', but latency faults apply to links.".formatted(what,
									node.name()))
					.atNode(node.id())
					.atPath(path)
					.withHint("Pick a link as the target, or use an outage/slowdown fault for a component."));
		}
		return Optional.of(Issue
			.error("fault_unknown_target",
					"%s targets link '%s', which does not exist.".formatted(what, fault.target()))
			.atPath(path)
			.withHint("Pick an existing link or delete the fault."));
	}

	/**
	 * What is wrong with the target of a fault that applies to components, which must be
	 * a part the clients call.
	 * @param fault the fault
	 * @param what how messages name the fault
	 * @param path where the fault is in the document
	 * @param node the node the fault targets, or {@code null} when it targets none
	 * @return the problem, or empty when the target is such a part
	 */
	private Optional<Issue> componentTargetProblem(Fault fault, String what, String path, @Nullable NodeSpec node) {
		if (node == null) {
			return Optional.of(missingComponent(fault, what, path));
		}
		if (this.context.isTrafficSource(node)) {
			return Optional.of(Issue
				.error("fault_wrong_target",
						"%s targets the traffic source '%s'; faults apply to the system, not the clients."
							.formatted(what, node.name()))
				.atNode(node.id())
				.atPath(path)
				.withHint("Target a component the clients call, or change the workload instead."));
		}
		return Optional.empty();
	}

	private Issue missingComponent(Fault fault, String what, String path) {
		if (this.context.hasEdge(fault.target())) {
			return Issue
				.error("fault_wrong_target",
						"%s targets link '%s', but %s faults apply to components.".formatted(what, fault.target(),
								fault.kind().json()))
				.atEdge(fault.target())
				.atPath(path)
				.withHint("Pick a component as the target, or use a latency fault for a link.");
		}
		return Issue
			.error("fault_unknown_target",
					"%s targets component '%s', which does not exist.".formatted(what, fault.target()))
			.atPath(path)
			.withHint("Pick an existing component or delete the fault.");
	}

	/**
	 * A fault that starts after the run ends, or runs past the end of its arrivals.
	 * @param fault the fault
	 * @param what how messages name the fault
	 * @return the problem, not yet pointed at the fault's target; empty when the fault
	 * lies inside the run
	 */
	private Optional<Issue> timingProblem(Fault fault, String what) {
		double duration = this.workload.durationS();
		double end = fault.startS() + fault.durationS();
		if (fault.startS() >= duration) {
			return Optional.of(Issue
				.error("fault_outside_run",
						"%s starts at %s s, after the run ends (%s s), so it would never happen.".formatted(what,
								seconds(fault.startS()), seconds(duration)))
				.withHint("Move the fault inside the run or make the run longer."));
		}
		if (end > duration) {
			return Optional.of(Issue
				.warning("fault_clipped",
						("%s runs until %s s but arrivals stop at %s s; only the part inside the run has an effect on"
								+ " new requests.")
							.formatted(what, seconds(end), seconds(duration)))
				.withHint("Shorten the fault or make the run longer."));
		}
		return Optional.empty();
	}

	/**
	 * A time as messages write it: Python's {@code g} format.
	 * @param value the time in seconds
	 * @return the time, without trailing zeros
	 */
	private static String seconds(double value) {
		return PythonFormat.format(value, "g");
	}

}
