package io.archsim.app.runs;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.web.ApiBody;

/**
 * Simulation runs: start them (they are simulated in the background), poll them, cancel,
 * reproduce and fetch their reports. Viewers read runs; editors start, cancel and delete
 * them. With limits on, starting runs counts against the workspace's runs per hour and
 * the asker's own ({@code 429} with {@code Retry-After}).
 */
@RestController
class RunController {

	private final Runs runs;

	private final RunStarts starts;

	private final RunChanges changes;

	private final Batches batches;

	RunController(Runs runs, RunStarts starts, Batches batches, RunChanges changes) {
		this.runs = runs;
		this.starts = starts;
		this.batches = batches;
		this.changes = changes;
	}

	/**
	 * Validate and queue a simulation; poll {@code GET /runs/{id}}. With {@code seeds}
	 * above 1, a seed group, answered with its first run ({@code groupId} names the
	 * group).
	 * @param principal who is asking
	 * @param projectId the project
	 * @param request what to run
	 * @return {@code 202} with the run
	 */
	@PostMapping("/projects/{projectId}/runs")
	ResponseEntity<RunView> create(Principal principal, @PathVariable String projectId, @ApiBody RunCreate request) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(this.starts.create(principal, projectId, request));
	}

	/**
	 * Queue several scenarios of the same design ({@code null}: the design itself).
	 * @param principal who is asking
	 * @param projectId the project
	 * @param request the scenarios
	 * @return {@code 202} with the runs
	 */
	@PostMapping("/projects/{projectId}/runs/batch")
	ResponseEntity<List<RunView>> batch(Principal principal, @PathVariable String projectId,
			@ApiBody BatchRunCreate request) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(this.batches.start(principal, projectId, request));
	}

	@GetMapping("/projects/{projectId}/runs")
	List<RunView> list(Principal principal, @PathVariable String projectId) {
		return this.runs.list(principal, projectId);
	}

	@GetMapping("/runs/{runId}")
	RunView get(Principal principal, @PathVariable String runId) {
		return this.runs.require(principal, runId, WorkspaceRole.VIEWER).view();
	}

	/**
	 * The full simulation report: provenance, summary, time series, per-node metrics,
	 * findings.
	 * @param principal who is asking
	 * @param runId the run
	 * @return the report
	 */
	@GetMapping("/runs/{runId}/report")
	ResponseEntity<String> report(Principal principal, @PathVariable String runId) {
		return this.runs.report(this.runs.require(principal, runId, WorkspaceRole.VIEWER)).answer();
	}

	/**
	 * The exact design submitted for this run, which can be imported again.
	 * @param principal who is asking
	 * @param runId the run
	 * @return the design
	 */
	@GetMapping("/runs/{runId}/input")
	ResponseEntity<String> input(Principal principal, @PathVariable String runId) {
		return this.runs.input(this.runs.require(principal, runId, WorkspaceRole.VIEWER)).answer();
	}

	@PostMapping("/runs/{runId}/cancel")
	RunView cancel(Principal principal, @PathVariable String runId) {
		return this.changes.cancel(principal, runId);
	}

	/**
	 * Simulate the exact stored input again (same design, scenario, seed and mode) to
	 * check it reproduces: the new run's {@code resultDigest} should equal the original's
	 * when the same engine simulates it (ADR-0050).
	 * @param principal who is asking
	 * @param runId the run to reproduce
	 * @return {@code 202} with the new run
	 */
	@PostMapping("/runs/{runId}/rerun")
	ResponseEntity<RunView> rerun(Principal principal, @PathVariable String runId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(this.changes.rerun(principal, runId));
	}

	@DeleteMapping("/runs/{runId}")
	ResponseEntity<Void> delete(Principal principal, @PathVariable String runId) {
		this.changes.delete(principal, runId);
		return ResponseEntity.noContent().build();
	}

}
