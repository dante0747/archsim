/**
 * A cache during a run: its shards' command executors, hits and misses (a fixed ratio, or
 * keys that expire after a TTL in a memory of their size), read-through and cache-aside
 * misses, snapshot forks, and what it adds to a report.
 */
@NullMarked
package io.archsim.engine.components.cache;

import org.jspecify.annotations.NullMarked;
