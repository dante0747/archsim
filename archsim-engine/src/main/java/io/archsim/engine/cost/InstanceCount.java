package io.archsim.engine.cost;

/**
 * The billable instances of a part, and how the count was derived.
 *
 * @param count the instances
 * @param basis how they were counted, such as {@code 2 shards × 3 nodes}
 */
record InstanceCount(double count, String basis) {
}
