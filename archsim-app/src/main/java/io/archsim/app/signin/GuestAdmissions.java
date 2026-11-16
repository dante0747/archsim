package io.archsim.app.signin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Principal;
import io.archsim.app.access.Visitor;
import io.archsim.app.web.ApiException;

/**
 * Admits a visitor as a sandbox's guest, all or nothing: the guest, their sandbox, and a
 * browser session that ends with it.
 */
@Service
class GuestAdmissions {

	private final Guests guests;

	private final Sessions sessions;

	private final Me me;

	GuestAdmissions(Guests guests, Sessions sessions, Me me) {
		this.guests = guests;
		this.sessions = sessions;
		this.me = me;
	}

	/**
	 * Admits a visitor, in a transaction of its own.
	 * @param visitor where they come from, which their session records
	 * @return the guest as {@code GET /auth/me} describes them, and the session's cookies
	 * @throws ApiException {@code 503} while every place is taken
	 */
	@Transactional
	StartedSandbox admit(Visitor visitor) {
		Guest guest = this.guests.add();
		StartedSession session = this.sessions.startUntil(guest.id(), visitor, guest.expiresAt());
		Me.View view = this.me.view(Principal.guest(guest.id(), session.id(), visitor.ip()), session.csrfToken());
		return new StartedSandbox(view, session.cookies());
	}

}
