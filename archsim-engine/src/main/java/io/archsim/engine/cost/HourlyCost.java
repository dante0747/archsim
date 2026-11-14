package io.archsim.engine.cost;

/**
 * What an hour of a design costs at the workload's steady base load and at its peak,
 * which a month bills for the rest of its hours and for its peak hours (ADR-0074).
 * Written by {@code RecordCodec}.
 *
 * @param base an hour at the base load
 * @param peak an hour at the peak, each autoscaled part at the count its autoscaler
 * settles at there
 */
public record HourlyCost(double base, double peak) {
}
