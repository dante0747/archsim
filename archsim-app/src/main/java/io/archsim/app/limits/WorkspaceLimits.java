package io.archsim.app.limits;

import java.util.Map;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;

/**
 * What each kind of workspace may use. With limits on ({@code ARCHSIM_RATE_LIMITS}, on
 * with sign-in) each kind has a hosted profile; without them only the server's own
 * settings apply, except to sandboxes, which always keep theirs. A workspace's own
 * {@code max_concurrent_runs} and {@code max_runs_per_hour} replace its kind's.
 */
@Component
public class WorkspaceLimits {

	/** The kind of workspace a visitor tries ArchSim in. */
	public static final String SANDBOX = "sandbox";

	/** The kind of workspace each person has. */
	private static final String PERSONAL = "personal";

	/** The kind of workspace people share. */
	private static final String TEAM = "team";

	/** Seeds one group runs, hosted or in a sandbox. */
	private static final int SEEDS = 5;

	/** Wall-clock seconds of a calibration's replay, hosted or in a sandbox. */
	private static final double CALIBRATE_SECONDS = 20.0;

	/** No limits beyond the server's own (local use), and ten seeds a group. */
	private static final LimitProfile UNLIMITED = new LimitProfile(null, null, null, null, null, null, 10);

	/**
	 * A sandbox's limits, with or without the hosted ones: one run at once, 20 an hour,
	 * at most 200,000 requests and 20 seconds a run, five projects.
	 */
	private static final LimitProfile SANDBOXED = new LimitProfile(1, 20, 200_000L, 20.0, CALIBRATE_SECONDS, 5, SEEDS);

	/**
	 * A personal workspace's hosted limits, and those of a kind the API does not know.
	 */
	private static final LimitProfile HOSTED_PERSONAL = hosted(2, 60);

	/** The hosted profile of each kind. */
	private static final Map<String, LimitProfile> HOSTED = Map.of(PERSONAL, HOSTED_PERSONAL, TEAM, hosted(4, 300),
			SANDBOX, SANDBOXED);

	private final ArchsimProperties properties;

	/**
	 * The limits under a server's settings.
	 * @param properties the settings: whether the limits apply, and the server's own
	 */
	public WorkspaceLimits(ArchsimProperties properties) {
		this.properties = properties;
	}

	/**
	 * The limits of a kind of workspace.
	 * @param kind {@code personal}, {@code team} or {@code sandbox}; an unknown kind is
	 * personal
	 * @return its profile
	 */
	public LimitProfile profile(String kind) {
		if (this.properties.limitsOn()) {
			return HOSTED.getOrDefault(kind, HOSTED_PERSONAL);
		}
		return isSandbox(kind) ? SANDBOXED : UNLIMITED;
	}

	/**
	 * The most projects a workspace of a kind holds.
	 * @param kind the workspace's kind
	 * @return the cap, or empty when there is none
	 */
	public OptionalInt maxProjects(String kind) {
		return optional(profile(kind).maxProjects());
	}

	/**
	 * Runs a workspace simulates at once.
	 * @param kind the workspace's kind
	 * @param own the workspace's own limit, if it has one
	 * @return its own limit, else its kind's; empty for no limit
	 */
	public OptionalInt maxConcurrentRuns(String kind, @Nullable Integer own) {
		return optional((own != null) ? own : profile(kind).maxConcurrentRuns());
	}

	/**
	 * Runs a workspace may start per hour.
	 * @param kind the workspace's kind
	 * @param own the workspace's own limit, if it has one
	 * @return its own limit, else, with limits on, its kind's; empty for no limit
	 */
	public OptionalInt runsPerHour(String kind, @Nullable Integer own) {
		if (own != null || !this.properties.limitsOn()) {
			return optional(own);
		}
		return optional(profile(kind).runsPerHour());
	}

	/**
	 * The largest run a workspace of a kind may start: the server's own limits, tightened
	 * by the kind's.
	 * @param kind the workspace's kind
	 * @return the most requests and wall-clock seconds of one run
	 */
	public RunLimits runLimits(String kind) {
		LimitProfile profile = profile(kind);
		Long requests = profile.maxRequests();
		Double seconds = profile.maxWallSeconds();
		return new RunLimits(
				(requests != null) ? Math.min(this.properties.maxRequests(), requests) : this.properties.maxRequests(),
				(seconds != null) ? Math.min(this.properties.maxWallSeconds(), seconds)
						: this.properties.maxWallSeconds());
	}

	/**
	 * The largest replay a calibration in a workspace of a kind may run. It runs while
	 * the request waits, so it gets the tightest wall clock: the server's, the
	 * calibration limit's and the kind's.
	 * @param kind the workspace's kind
	 * @param calibrateMaxWallSeconds the server's limit on a calibration's replay
	 * ({@code ARCHSIM_CALIBRATE_MAX_WALL_SECONDS})
	 * @return the most requests and wall-clock seconds of the replay
	 */
	public RunLimits replayLimits(String kind, double calibrateMaxWallSeconds) {
		RunLimits run = runLimits(kind);
		double seconds = Math.min(run.maxWallSeconds(), calibrateMaxWallSeconds);
		Double kindSeconds = profile(kind).calibrateWallSeconds();
		return new RunLimits(run.maxRequests(), (kindSeconds != null) ? Math.min(seconds, kindSeconds) : seconds);
	}

	/**
	 * Whether a workspace of a kind is a sandbox.
	 * @param kind the workspace's kind
	 * @return {@code true} for a sandbox
	 */
	public static boolean isSandbox(String kind) {
		return SANDBOX.equals(kind);
	}

	/**
	 * The most runs one seed group of a workspace of a kind may start.
	 * @param kind the workspace's kind
	 * @return the most seeds
	 */
	public int maxSeeds(String kind) {
		return profile(kind).maxSeeds();
	}

	/**
	 * A personal or team workspace's hosted limits: runs at once and per hour; the
	 * server's own run sizes, any number of projects.
	 * @param atOnce runs simulated at once
	 * @param perHour runs started per hour
	 * @return the profile
	 */
	private static LimitProfile hosted(int atOnce, int perHour) {
		return new LimitProfile(atOnce, perHour, null, null, CALIBRATE_SECONDS, null, SEEDS);
	}

	private static OptionalInt optional(@Nullable Integer limit) {
		return (limit != null) ? OptionalInt.of(limit) : OptionalInt.empty();
	}

	/**
	 * The largest run a workspace may start.
	 *
	 * @param maxRequests expected requests in one run
	 * @param maxWallSeconds wall-clock seconds one run may take
	 */
	public record RunLimits(long maxRequests, double maxWallSeconds) {

	}

}
