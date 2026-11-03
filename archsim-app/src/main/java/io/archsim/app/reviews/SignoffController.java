package io.archsim.app.reviews;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiBody;

/**
 * Review sign-offs: a reviewer approves a run of a design or asks for changes, with a
 * note. A sign-off records the project's revision and the run's digests, and goes stale
 * once the design is saved again. Viewers sign off too; a reviewer may withdraw their
 * own.
 */
@RestController
class SignoffController {

	private final Signoffs signoffs;

	private final SignoffChanges changes;

	private final SignoffWithdrawals withdrawals;

	SignoffController(Signoffs signoffs, SignoffChanges changes, SignoffWithdrawals withdrawals) {
		this.signoffs = signoffs;
		this.changes = changes;
		this.withdrawals = withdrawals;
	}

	/**
	 * Approve a finished run of the design, or request changes. It applies to the design
	 * as saved now (its revision) and to this run's input and result digests.
	 * @param principal who is asking
	 * @param runId the run
	 * @param request the decision and its note
	 * @return {@code 201} with the sign-off
	 */
	@PostMapping("/runs/{runId}/signoffs")
	ResponseEntity<SignoffView> signOff(Principal principal, @PathVariable String runId,
			@ApiBody SignoffCreate request) {
		SignoffView created = this.signoffs.view(this.changes.create(principal, runId, request), principal);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	/**
	 * Sign-offs of one run, newest first.
	 * @param principal who is asking
	 * @param runId the run
	 * @return the sign-offs
	 */
	@GetMapping("/runs/{runId}/signoffs")
	List<SignoffView> ofRun(Principal principal, @PathVariable String runId) {
		return this.signoffs.ofRun(principal, runId);
	}

	/**
	 * Every sign-off of the project's runs, newest first; {@code stale} ones predate the
	 * design as saved now.
	 * @param principal who is asking
	 * @param projectId the project
	 * @return the sign-offs
	 */
	@GetMapping("/projects/{projectId}/signoffs")
	List<SignoffView> ofProject(Principal principal, @PathVariable String projectId) {
		return this.signoffs.ofProject(principal, projectId);
	}

	/**
	 * Withdraw your own sign-off.
	 * @param principal who is asking
	 * @param signoffId the sign-off
	 * @return {@code 204}
	 */
	@DeleteMapping("/signoffs/{signoffId}")
	ResponseEntity<Void> withdraw(Principal principal, @PathVariable String signoffId) {
		this.withdrawals.withdraw(principal, signoffId);
		return ResponseEntity.noContent().build();
	}

}
