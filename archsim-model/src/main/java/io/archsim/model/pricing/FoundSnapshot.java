package io.archsim.model.pricing;

/**
 * A snapshot this machine has, with the file it was read from.
 *
 * @param snapshot the snapshot
 * @param file the name of the file it was read from
 * @param shipped whether it is one of the snapshots ArchSim ships
 */
record FoundSnapshot(PriceSnapshot snapshot, String file, boolean shipped) {
}
