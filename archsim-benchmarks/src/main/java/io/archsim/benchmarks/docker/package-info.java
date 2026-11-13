/**
 * The benchmark's stacks in Docker: their containers and the CPUs each is pinned to and
 * how those fall on physical cores, {@code docker compose}, the containers' CPU and
 * memory from their cgroups, the services' HTTP endpoints, the clock, and starting and
 * stopping a stack.
 */
@NullMarked
package io.archsim.benchmarks.docker;

import org.jspecify.annotations.NullMarked;
