/**
 * The price refresh: snapshots of a provider's public list prices in ArchSim's format
 * ({@code archsim.prices/1}), written among the ones archsim-model ships, and a summary
 * of what changed since the last. Run by hand or by the refresh workflow; ArchSim never
 * calls a price API at runtime. A snapshot is written as {@code scripts/prices} wrote it
 * in Python (ADR-0072), so that the next one diffs cleanly against the last.
 */
@NullMarked
package io.archsim.tools.prices;

import org.jspecify.annotations.NullMarked;
