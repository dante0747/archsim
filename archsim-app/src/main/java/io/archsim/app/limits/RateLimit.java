package io.archsim.app.limits;

/**
 * How many of something one key may do in a fixed window of time.
 *
 * @param limit how many per window
 * @param windowSeconds the window's length
 */
public record RateLimit(int limit, long windowSeconds) {

	/** A minute, in seconds. */
	public static final long MINUTE = 60;

	/** An hour, in seconds. */
	public static final long HOUR = 3600;

	/** A day, in seconds. */
	public static final long DAY = 86_400;

	/** Sign-ins started from one address: 30 per 10 minutes. */
	public static final RateLimit LOGINS_PER_IP = new RateLimit(30, 600);

	/** Sandboxes started from one address: 5 an hour. */
	public static final RateLimit SANDBOXES_PER_IP = new RateLimit(5, HOUR);

	/** API tokens one person makes per day. */
	public static final RateLimit TOKENS_PER_USER = new RateLimit(20, DAY);

	/** Team workspaces one person makes per day. */
	public static final RateLimit WORKSPACES_PER_USER = new RateLimit(10, DAY);

	/** Invitations one workspace sends per day. */
	public static final RateLimit INVITES_PER_WORKSPACE = new RateLimit(50, DAY);

	/** Calibrations one person runs per hour. */
	public static final RateLimit CALIBRATIONS_PER_USER = new RateLimit(30, HOUR);

	/** Runs one person starts per hour, whatever the workspace. */
	public static final RateLimit RUNS_PER_USER = new RateLimit(300, HOUR);

	/** Comments one person writes per hour. */
	public static final RateLimit COMMENTS_PER_USER = new RateLimit(240, HOUR);

	/** Sign-offs one person gives per hour. */
	public static final RateLimit SIGNOFFS_PER_USER = new RateLimit(60, HOUR);

	/** Share links one person makes per day. */
	public static final RateLimit SHARES_PER_USER = new RateLimit(100, DAY);

	/** Share pages opened from one address: 60 a minute. */
	public static final RateLimit PUBLIC_VIEWS_PER_IP = new RateLimit(60, MINUTE);

	/**
	 * Requests one person makes to the engine's routes: 600 a minute, about 1.75 times
	 * the most the editor sends, a validation and an analysis after each pause of 350 ms
	 * (ADR-0108).
	 */
	public static final RateLimit ENGINE_REQUESTS_PER_USER = new RateLimit(600, MINUTE);

}
