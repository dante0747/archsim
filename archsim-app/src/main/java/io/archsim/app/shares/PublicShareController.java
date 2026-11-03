package io.archsim.app.shares;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.Visitor;
import io.archsim.app.projects.ProjectView;
import io.archsim.app.web.ApiBody;
import io.archsim.app.web.QueryParameters;

/**
 * What a share link opens: the shared report, for anyone with the link, and a copy of its
 * design, for someone signed in.
 */
@RestController
class PublicShareController {

	private final PublicShares shares;

	private final ShareCopies copies;

	PublicShareController(PublicShares shares, ShareCopies copies) {
		this.shares = shares;
		this.copies = copies;
	}

	/**
	 * A shared report, for anyone with the link (no sign-in). {@code view=false} reads it
	 * without counting a view (link previews). The answer is neither cached nor indexed,
	 * even when the link does not work (FastAPI's {@code 404} carried neither header).
	 * @param slug the link's id
	 * @param view whether this counts as a view
	 * @param visitor where the request comes from, for the limit per address
	 * @param response the answer, to mark before the link is looked up
	 * @return the report
	 */
	@GetMapping("/public/shares/{slug}")
	PublicShareView open(@PathVariable String slug,
			@RequestParam(name = "view", required = false) @Parameter(
					schema = @Schema(type = "boolean", defaultValue = "true")) @Nullable List<String> view,
			Visitor visitor, HttpServletResponse response) {
		boolean counted = QueryParameters.flag(view, "view").orElse(true);
		this.shares.admit(visitor);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader("X-Robots-Tag", "noindex");
		return this.shares.open(slug, counted);
	}

	/**
	 * Open a copy: a new project in your workspace from the design a share link shows
	 * (the exact design that was simulated).
	 * @param principal who is asking
	 * @param request the link and the new project's name
	 * @param workspace the workspace the request names
	 * @return {@code 201} with the project
	 */
	@PostMapping("/projects/from-share")
	ResponseEntity<ProjectView> copy(Principal principal, @ApiBody CopyFromShare request,
			RequestedWorkspace workspace) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.copies.copy(principal, workspace, request));
	}

}
