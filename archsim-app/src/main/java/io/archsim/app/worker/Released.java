package io.archsim.app.worker;

/**
 * What became of a run its worker gave back unfinished, as it stopped.
 */
enum Released {

	/** Queued again, its attempt not counted. */
	QUEUED("queued"),

	/** Cancelled, as someone had asked. */
	CANCELLED("cancelled");

	private final String status;

	Released(String status) {
		this.status = status;
	}

	/**
	 * The run's status now.
	 * @return {@code queued} or {@code cancelled}
	 */
	String status() {
		return this.status;
	}

}
