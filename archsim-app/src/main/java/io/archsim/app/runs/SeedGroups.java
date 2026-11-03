package io.archsim.app.runs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.archsim.app.access.Principal;
import io.archsim.app.engine.PreparedRun;
import io.archsim.app.web.ApiException;
import io.archsim.model.document.Workload;

/**
 * Seed groups: one input run with consecutive seeds, from the design's own seed on, read
 * together with {@code GET /run-groups/{id}}. Every seed is prepared before the group is
 * counted or queued, and the group is queued with its runs in one transaction.
 */
@Component
class SeedGroups {

	private final RunPlanner planner;

	private final RunAdmission admission;

	SeedGroups(RunPlanner planner, RunAdmission admission) {
		this.planner = planner;
		this.admission = admission;
	}

	/**
	 * Starts a seed group.
	 * @param principal who starts it
	 * @param start the design prepared with its own seed, the group's base
	 * @param request how many seeds, and the label and mode
	 * @return the group's first run
	 * @throws ApiException {@code 422} when the group is bigger than the workspace runs
	 * or its seeds go past the largest
	 */
	RunView start(Principal principal, RunStart start, RunCreate request) {
		RunPlanner.Plan plan = start.plan();
		int seeds = request.seeds();
		if (seeds > plan.maxSeeds()) {
			throw refused(plan.workspace().name() + " can run at most " + plan.maxSeeds() + " seeds at once.");
		}
		long base = start.first().seed();
		long last = base + seeds - 1;
		if (last > Workload.MAX_SEED) {
			throw refused(String.format(Locale.ROOT, "Seeds %,d to %,d go past the largest, %,d.", base, last,
					Workload.MAX_SEED));
		}
		String label = RunLabels.named(request.label(), start.first().scenarioName());
		List<NewRun> runs = new ArrayList<>();
		for (int index = 0; index < seeds; index++) {
			String runLabel = RunLabels.cut(label + " · seed " + (index + 1) + " of " + seeds);
			runs.add(new NewRun(start.destination(), prepareSeed(start, request, base + index), runLabel,
					request.mode()));
		}
		NewGroup group = new NewGroup(start.destination(), base, seeds, request.mode(), start.first().scenarioId(),
				label);
		return this.admission.admitGroup(plan.workspace(), principal, group, runs).getFirst();
	}

	/**
	 * Prepares one of the group's runs, with its own seed: the engine sets the seed after
	 * resolving the scenario.
	 * @param start the group's start
	 * @param request what the group runs
	 * @param seed the run's seed
	 * @return what the engine prepared
	 */
	private PreparedRun prepareSeed(RunStart start, RunCreate request, long seed) {
		return this.planner.prepare(start.plan().request(start.document(), request.scenarioId(), seed));
	}

	private static ApiException refused(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message, List.of());
	}

}
