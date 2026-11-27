package io.archsim.app.runs;

import java.time.Instant;

import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.retention.Retention;

/**
 * The runs past their project's limit ({@code ARCHSIM_RUN_HISTORY_LIMIT}): a project
 * keeps its newest runs, the ones its list shows, and the older runs it still needs, and
 * the others go, oldest first. It still needs a run a worker has yet to finish (queued or
 * running), a run someone signed off or commented on, which the review would lose, and
 * every run of a seed group it keeps any run of, as the group's summary reads them all. A
 * seed group goes with its last run. A share link is no reason to keep a run: it holds a
 * copy of the results, which outlives the run.
 */
@Component
class RunRetention implements Retention {

	/**
	 * The most runs one statement deletes: each takes its report with it, and its design
	 * when no other run simulates it.
	 */
	private static final int BATCH = 100;

	private final RunRepository runs;

	private final RunGroupRepository groups;

	private final ArchsimProperties properties;

	RunRetention(RunRepository runs, RunGroupRepository groups, ArchsimProperties properties) {
		this.runs = runs;
		this.groups = groups;
		this.properties = properties;
	}

	@Override
	public String description() {
		return "runs past their project's limit";
	}

	/**
	 * Deletes, in each project with more runs than it keeps, the runs it no longer needs,
	 * then the seed groups left without runs.
	 * @param now the time it is, which does not matter: runs are kept by their number
	 * @return how many runs it deleted
	 */
	@Override
	public int prune(Instant now) {
		int kept = this.properties.runHistoryLimit();
		int pruned = 0;
		for (String projectId : this.runs.projectsWithMoreRunsThan(kept)) {
			pruned += Retention.inBatches(() -> this.runs.prunePastLimit(projectId, kept, BATCH));
			this.groups.deleteEmpty(projectId);
		}
		return pruned;
	}

}
