package io.archsim.model.pricing;

/**
 * A snapshot that can be chosen: which prices it has and whether it is the newest of its
 * provider and region.
 *
 * @param provider the provider
 * @param region the region
 * @param date the day of its prices
 * @param file the file it was read from
 * @param sha256 the hash of its content
 * @param newest whether no snapshot of its provider and region is newer
 */
public record AvailableSnapshot(String provider, String region, String date, String file, String sha256,
		boolean newest) {
}
