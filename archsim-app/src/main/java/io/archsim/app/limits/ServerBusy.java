package io.archsim.app.limits;

import io.archsim.app.web.ApiException;

/**
 * The answers to a request the server has no room for now (ADR-0108): {@code 503} with
 * when to try again. The replay pool gives the calibrations' answer too, when its queue
 * is full.
 */
public final class ServerBusy {

	/** When a quick request may try again, in seconds. */
	private static final long QUICK_RETRY_AFTER = 1;

	/**
	 * When a calibration may try again, in seconds: a replay takes seconds to minutes.
	 */
	private static final long REPLAY_RETRY_AFTER = 10;

	private ServerBusy() {
	}

	/**
	 * A request to one of the engine's quick routes that found no slot free in time.
	 * @return the refusal
	 */
	static ApiException designs() {
		return ApiException.unavailable("The server is busy with other designs: try again in a moment",
				QUICK_RETRY_AFTER);
	}

	/**
	 * A calibration that found no replay slot, or thread, free in time.
	 * @return the refusal
	 */
	public static ApiException replays() {
		return ApiException.unavailable("The server is replaying other calibrations: calibrate again in a moment",
				REPLAY_RETRY_AFTER);
	}

	/**
	 * Another request with a body that found no slot free in time.
	 * @return the refusal
	 */
	static ApiException requests() {
		return ApiException.unavailable("The server is busy: try again in a moment", QUICK_RETRY_AFTER);
	}

	/**
	 * A request stopped while it waited for a slot, as when the server stops.
	 * @return the refusal
	 */
	static ApiException stopping() {
		return ApiException.unavailable("The server is stopping: try again in a moment", QUICK_RETRY_AFTER);
	}

}
