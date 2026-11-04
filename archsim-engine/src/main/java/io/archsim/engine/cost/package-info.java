/**
 * The cost estimate: a design's monthly cost from each part's price per instance-hour
 * (set on the part, a price snapshot's instance type, a preset, its size at generic
 * rates, or the catalog default), managed and per-request parts at a snapshot's rates,
 * and each line's basis in words.
 */
@NullMarked
package io.archsim.engine.cost;

import org.jspecify.annotations.NullMarked;
