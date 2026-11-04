package io.archsim.engine.estimate;

/**
 * The part that uses most of its slots or CPU.
 *
 * @param id its id
 * @param label its label
 * @param utilization the share of its slots or CPU in use
 * @param limitedBy what it uses most of
 */
public record Busiest(String id, String label, double utilization, Limit limitedBy) {
}
