package io.archsim.benchmarks.docker;

/**
 * A clock that moves only when the code waits: each wait takes no time but moves the
 * clock on by as long, so that a campaign's timing runs at once and the same every time.
 */
public final class FakeTicker implements Ticker {

	private double now;

	/**
	 * A clock at a moment.
	 * @param start the moment, in seconds since the epoch
	 */
	public FakeTicker(double start) {
		this.now = start;
	}

	@Override
	public synchronized double now() {
		return this.now;
	}

	@Override
	public synchronized void sleep(double seconds) {
		this.now += seconds;
	}

}
