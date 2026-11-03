package io.archsim.app.access;

/**
 * Someone asking in person, from a browser or without sign-in, never with an API token:
 * what managing access itself (tokens, invitations, workspaces) takes. A controller that
 * takes one refuses API tokens with {@code 403} before it reads the request's body.
 *
 * @param principal who is asking
 */
public record Person(Principal principal) {

	/**
	 * The person's user id.
	 * @return the id
	 */
	public String userId() {
		return this.principal.userId();
	}

}
