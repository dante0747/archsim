package io.archsim.app.workspaces;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiBody;

/**
 * Invitation links, as the person who got one sees them.
 */
@RestController
@RequestMapping("/invitations")
class InvitationController {

	private final Acceptances acceptances;

	InvitationController(Acceptances acceptances) {
		this.acceptances = acceptances;
	}

	/**
	 * What accepting an invitation would do: the workspace, the role and who sent it.
	 * @param principal who is asking
	 * @param request the invitation's token
	 * @return the preview
	 */
	@PostMapping("/preview")
	InvitationPreview preview(Principal principal, @ApiBody InvitationToken request) {
		return this.acceptances.preview(principal, request.token());
	}

	/**
	 * Join the workspace an invitation is for. A member keeps the stronger of the two
	 * roles.
	 * @param person who accepts
	 * @param request the invitation's token
	 * @return the workspace, with your role there
	 */
	@PostMapping("/accept")
	WorkspaceView accept(Person person, @ApiBody InvitationToken request) {
		return this.acceptances.accept(person, request.token());
	}

}
