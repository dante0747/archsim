package io.archsim.engine.runtime;

/**
 * The machine an instance's CPU work runs on: the design's host, which every part with a
 * CPU size shares ({@link SharedHost}); the instance's own hyperthreads
 * ({@link OwnHyperthreads}); or full cores at a fixed speed ({@link FullCores}). A piece
 * of work takes its length over the speed of a thread at the number of busy threads when
 * it starts.
 */
public sealed interface CpuMachine permits SharedHost, OwnHyperthreads, FullCores {

	/**
	 * Counts a thread in for a piece of CPU work.
	 * @param workMs the work at full speed
	 * @return how long it takes at the speed of the threads now busy
	 */
	double start(double workMs);

	/**
	 * Counts a thread out, its work done.
	 */
	void stop();

	/**
	 * A part's CPU ceiling on this machine, from the ceiling were every one of its vCPUs
	 * to run at full speed.
	 * @param fullSpeed the ceiling at full speed, in requests per second
	 * @param ownThreads the part's vCPUs over its instances
	 * @param observedRps the part's arrival rate over the window
	 * @return the ceiling on the machine; {@code fullSpeed} itself when it is not a
	 * positive, finite rate or the part has no vCPU
	 */
	double capacity(double fullSpeed, double ownThreads, double observedRps);

	/**
	 * Whether a ceiling at full speed is one a machine slows: a positive, finite rate of
	 * a part with vCPUs.
	 * @param fullSpeed the ceiling at full speed
	 * @param ownThreads the part's vCPUs
	 * @return {@code true} when the machine's speed applies to it
	 */
	static boolean slows(double fullSpeed, double ownThreads) {
		return fullSpeed > 0 && Double.isFinite(fullSpeed) && ownThreads > 0;
	}

}
