package io.archsim.engine.runtime;

/**
 * Full cores at a fixed speed: an instance without a CPU size; or, on a design without a
 * host, one whose vCPUs are not hyperthreads. Work takes its length, and a ceiling is the
 * ceiling at full speed.
 */
public enum FullCores implements CpuMachine {

	/** The one machine of full cores. */
	INSTANCE;

	@Override
	public double start(double workMs) {
		return workMs;
	}

	@Override
	public void stop() {
		// nothing to count out
	}

	@Override
	public double capacity(double fullSpeed, double ownThreads, double observedRps) {
		return fullSpeed;
	}

}
