package io.archsim.engine.host;

/**
 * A machine at a load.
 *
 * @param busyThreads the threads that want the CPU
 * @param speed the mean speed each runs at, as a share of its speed alone
 */
public record HostLoad(double busyThreads, double speed) {

}
