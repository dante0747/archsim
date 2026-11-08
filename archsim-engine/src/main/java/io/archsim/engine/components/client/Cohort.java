package io.archsim.engine.components.client;

/**
 * How the user requests of a cohort (those of the warm-up, or of the measurement window)
 * ended so far.
 */
public final class Cohort {

	private long arrived;

	private long succeeded;

	private long errors;

	private long timeouts;

	void arrive() {
		this.arrived++;
	}

	void succeed() {
		this.succeeded++;
	}

	void fail() {
		this.errors++;
	}

	void timeOut() {
		this.timeouts++;
	}

	public long arrived() {
		return this.arrived;
	}

	public long succeeded() {
		return this.succeeded;
	}

	public long errors() {
		return this.errors;
	}

	public long timeouts() {
		return this.timeouts;
	}

	/**
	 * Two cohorts together, such as the warm-up's and the measured requests.
	 * @param other the other cohort
	 * @return a cohort counting the requests of both
	 */
	public Cohort plus(Cohort other) {
		Cohort both = new Cohort();
		both.arrived = this.arrived + other.arrived;
		both.succeeded = this.succeeded + other.succeeded;
		both.errors = this.errors + other.errors;
		both.timeouts = this.timeouts + other.timeouts;
		return both;
	}

	/**
	 * The requests still in flight.
	 * @return those that neither succeeded, failed nor timed out
	 */
	public long outstanding() {
		return this.arrived - this.succeeded - this.errors - this.timeouts;
	}

}
