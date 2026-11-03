package io.archsim.app.shares;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.web.ApiBody;

/**
 * Share links: a frozen, read-only copy of one run's results that anyone with the link
 * can open, without signing in, until it expires or is revoked. Editors make and revoke
 * them; viewers list them.
 */
@RestController
class ShareController {

	private final ShareLinks links;

	private final Shares shares;

	private final ShareRevocations revocations;

	private final ShareViews views;

	ShareController(ShareLinks links, Shares shares, ShareRevocations revocations, ShareViews views) {
		this.links = links;
		this.shares = shares;
		this.revocations = revocations;
		this.views = views;
	}

	/**
	 * A share link to this finished run's results: a frozen copy that does not change
	 * when the design does, or disappear with the run.
	 * @param principal who is asking
	 * @param runId the run
	 * @param request its title and how long it works
	 * @return {@code 201} with the link
	 */
	@PostMapping("/runs/{runId}/shares")
	ResponseEntity<ShareView> create(Principal principal, @PathVariable String runId, @ApiBody ShareCreate request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(this.views.of(this.links.create(principal, runId, request)));
	}

	/**
	 * The project's share links, newest first, revoked and expired ones included.
	 * @param principal who is asking
	 * @param projectId the project
	 * @return the links
	 */
	@GetMapping("/projects/{projectId}/shares")
	List<ShareView> ofProject(Principal principal, @PathVariable String projectId) {
		return this.views.of(this.shares.ofProject(principal, projectId));
	}

	/**
	 * Every share link of the workspace, newest first, including those whose project was
	 * deleted (they keep working until they expire or are revoked).
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @return the links
	 */
	@GetMapping("/shares")
	List<ShareView> ofWorkspace(Principal principal, RequestedWorkspace workspace) {
		return this.views.of(this.shares.ofWorkspace(principal, workspace));
	}

	/**
	 * Stop a link working, at once: the page answers {@code 404} and the stored copy is
	 * deleted.
	 * @param principal who is asking
	 * @param shareId the share
	 * @return {@code 204}
	 */
	@DeleteMapping("/shares/{shareId}")
	ResponseEntity<Void> revoke(Principal principal, @PathVariable String shareId) {
		this.revocations.revoke(principal, shareId);
		return ResponseEntity.noContent().build();
	}

}
