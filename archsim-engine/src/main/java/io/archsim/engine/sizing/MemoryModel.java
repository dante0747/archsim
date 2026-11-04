package io.archsim.engine.sizing;

/**
 * A service's memory: fixed, plus a share per request held and per request per second
 * served. Its typical memory is
 * {@code fixedGib + perRequestGib x the requests one pool holds
 * (arrival rate x time in the part) + perRpsGib x the rate one pool serves}.
 *
 * @param fixedGib the memory at idle, with the operating system on a VM of its own
 * @param perRequestGib the memory per request held
 * @param pools the pools the requests are spread over: the replicas
 * @param perRpsGib the memory per request per second served
 */
public record MemoryModel(double fixedGib, double perRequestGib, double pools, double perRpsGib) {

}
