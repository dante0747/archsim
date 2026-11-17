package io.archsim.engine.runtime;

/**
 * What the run's measured user requests lost of the messages they published,
 * dead-lettered or dropped, by the end of the run (ADR-0084): the messages, and the
 * requests that succeeded but lost one, each counted once, which availability counts as
 * requests that failed.
 */
public final class MessageLosses {

	private long messages;

	private long requests;

	/**
	 * Counts a message a measured request lost.
	 */
	void countMessage() {
		this.messages++;
	}

	/**
	 * Counts a measured request that succeeded and lost a message.
	 */
	void countRequest() {
		this.requests++;
	}

	/**
	 * The messages counted.
	 * @return how many
	 */
	public long messages() {
		return this.messages;
	}

	/**
	 * The requests counted.
	 * @return how many
	 */
	public long requests() {
		return this.requests;
	}

}
