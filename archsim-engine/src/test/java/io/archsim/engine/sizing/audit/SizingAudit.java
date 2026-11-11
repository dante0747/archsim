package io.archsim.engine.sizing.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;

/**
 * Auto-sizing's acceptance criteria (docs/simulation-model.md, "Auto-sizing"), checked on
 * a template's run: each auto-sized part's CPU ({@link CpuCheck}), the bottlenecks and
 * memory ({@link ResourceChecks}), a re-run and a higher rate ({@link ScaleChecks}), the
 * per-request cost lines ({@link PerRequestCheck}) and the monthly cost against a
 * hand-picked reference. It reads the run's report as JSON.
 */
final class SizingAudit {

	/**
	 * The monthly cost is at most this many times the template's hand-picked reference.
	 */
	private static final double COST_SLACK = 1.15;

	private SizingAudit() {
	}

	/**
	 * Every criterion on a template, its run compressed to {@code seconds}.
	 * @param design the template's design, as drawn
	 * @param seconds how long the run is compressed to
	 * @param reference the template's hand-picked monthly cost
	 * @return the checks, in the order the criteria are listed above
	 */
	static List<AuditCheck> audit(JsonNode design, double seconds, double reference) {
		AuditedRun run = TemplateRuns.run(TemplateRuns.compressed(design, seconds));
		List<AuditCheck> checks = new ArrayList<>(new CpuCheck(run).checks());
		checks.addAll(ResourceChecks.bottlenecks(run));
		checks.addAll(ResourceChecks.memory(run));
		checks.add(ScaleChecks.rerun(design));
		checks.addAll(ScaleChecks.monotonic(design));
		checks.addAll(new PerRequestCheck(run).checks());
		checks.add(new AuditCheck("*", "cost", run.monthly() <= COST_SLACK * reference,
				String.format(Locale.ROOT, "$%,.0f vs reference $%,.0f", run.monthly(), reference)));
		return checks;
	}

}
