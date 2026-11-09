package io.archsim.app.worker;

/**
 * The runs a reap settled, their leases run out.
 *
 * @param requeued queued again for another attempt
 * @param failed failed after their last attempt
 * @param cancelled cancelled, as someone asked
 */
record Reaped(int requeued, int failed, int cancelled) {

	/**
	 * The runs settled.
	 * @return how many
	 */
	int total() {
		return this.requeued + this.failed + this.cancelled;
	}

}
