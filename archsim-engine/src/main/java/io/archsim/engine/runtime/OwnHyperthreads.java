package io.archsim.engine.runtime;

import io.archsim.engine.host.HostMachine;

/**
 * One instance's own vCPUs as hyperthreads, two per physical core, with nothing else on
 * them: a vCPU whose sibling is busy too runs at the sibling speed.
 */
public final class OwnHyperthreads implements CpuMachine {

	private final HostMachine machine;

	private int busy;

	/**
	 * An instance's hyperthreads.
	 * @param machine its vCPUs as hyperthreads
	 */
	public OwnHyperthreads(HostMachine machine) {
		this.machine = machine;
	}

	@Override
	public double start(double workMs) {
		this.busy++;
		return workMs / this.machine.speed(this.busy);
	}

	@Override
	public void stop() {
		this.busy--;
	}

	/**
	 * Every vCPU busy, each at the speed of a full machine.
	 */
	@Override
	public double capacity(double fullSpeed, double ownThreads, double observedRps) {
		if (!CpuMachine.slows(fullSpeed, ownThreads)) {
			return fullSpeed;
		}
		return fullSpeed * this.machine.speed(this.machine.threads());
	}

}
