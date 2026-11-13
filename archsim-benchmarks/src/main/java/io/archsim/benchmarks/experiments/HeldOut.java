package io.archsim.benchmarks.experiments;

/**
 * The registration of a held-out experiment: registered before any engine was tuned on
 * it, it stays sealed (smoke tests only) until a frozen engine has predicted it.
 *
 * @param registered the date it was registered
 * @param commit the commit that registered it: its stack, workload and model
 */
public record HeldOut(String registered, String commit) {

}
