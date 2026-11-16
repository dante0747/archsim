package io.archsim.app.signin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import io.archsim.app.access.Visitor;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.web.ApiException;

/**
 * Trying ArchSim without signing in (ADR-0079): a visitor's browser starts a sandbox,
 * which admits them as a guest who owns it and signs the browser in as the guest until
 * the sandbox is deleted. Only where sandboxes are offered, for a browser not signed in
 * already, and as often as {@link RateLimit#SANDBOXES_PER_IP} allows an address. The
 * start is counted against the address before the admission's transaction begins, so a
 * request holds one database connection at a time.
 */
@Service
class Sandboxes {

	private final SandboxOffer offer;

	private final Sessions sessions;

	private final RateLimits limits;

	private final GuestAdmissions admissions;

	Sandboxes(SandboxOffer offer, Sessions sessions, RateLimits limits, GuestAdmissions admissions) {
		this.offer = offer;
		this.sessions = sessions;
		this.limits = limits;
		this.admissions = admissions;
	}

	/**
	 * Starts a sandbox for a visitor: the count of starts from their address, which
	 * counts even a start that then fails, then the guest, the sandbox and the session,
	 * all or nothing.
	 * @param visitor where they come from
	 * @param sessionCookie the session cookie their browser sent, if any
	 * @return the guest as {@code /auth/me} describes them, and the session's cookies
	 * @throws ApiException {@code 404} where sandboxes are not offered, {@code 409} for a
	 * browser signed in already, {@code 429} after many from one address, {@code 503}
	 * while every place is taken
	 */
	StartedSandbox start(Visitor visitor, @Nullable String sessionCookie) {
		if (!this.offer.offered()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "Sandboxes are not offered here", List.of());
		}
		if (this.sessions.signsIn(sessionCookie)) {
			throw ApiException.conflict("You are signed in already: sign out first to try a sandbox.");
		}
		this.limits.enforce("ip:" + visitor.ipOrUnknown() + ":sandboxes", RateLimit.SANDBOXES_PER_IP,
				"Too many sandboxes from your address.");
		return this.admissions.admit(visitor);
	}

}
