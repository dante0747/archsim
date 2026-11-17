package io.archsim.engine.runtime;

/**
 * What became of a user request beyond its answer (ADR-0084): whether its client got a
 * success, and whether a message it published was lost. Its attempts and the messages it
 * published share it, so a measured request that succeeded and lost a message counts once
 * in the run's losses, whichever came first and however many messages it lost.
 */
public final class RequestFate {

	private final MessageLosses losses;

	private final boolean measured;

	private boolean succeeded;

	private boolean lost;

	/**
	 * The fate of a request as it arrives.
	 * @param losses where the run counts the messages and requests that were lost
	 * @param measured whether the request arrived inside the measurement window
	 */
	public RequestFate(MessageLosses losses, boolean measured) {
		this.losses = losses;
		this.measured = measured;
	}

	/**
	 * Notes that the request's client got a success.
	 */
	public void succeeded() {
		boolean before = both();
		this.succeeded = true;
		countWhenBoth(before);
	}

	/**
	 * Notes that a message the request published was lost.
	 */
	public void lostAMessage() {
		boolean before = both();
		this.lost = true;
		if (this.measured) {
			this.losses.countMessage();
		}
		countWhenBoth(before);
	}

	private boolean both() {
		return this.succeeded && this.lost;
	}

	private void countWhenBoth(boolean before) {
		if (!before && both() && this.measured) {
			this.losses.countRequest();
		}
	}

}
