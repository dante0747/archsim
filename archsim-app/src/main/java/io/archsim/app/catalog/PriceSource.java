package io.archsim.app.catalog;

import org.jspecify.annotations.Nullable;

/**
 * A source of prices a design can name in its {@code pricing}: ArchSim's illustrative
 * rates, or a price snapshot on this server.
 */
sealed interface PriceSource permits PriceSource.Generic, PriceSource.Snapshot {

	/**
	 * ArchSim's illustrative rates, always available.
	 *
	 * @param provider always {@code generic}
	 * @param region always {@code null}: the rates are not a region's
	 * @param date always {@code null}: the rates are not a day's
	 * @param illustrative always {@code true}
	 * @param note what the rates are
	 */
	record Generic(String provider, @Nullable String region, @Nullable String date, boolean illustrative,
			String note) implements PriceSource {

	}

	/**
	 * A price snapshot of a provider's region on a day.
	 *
	 * @param provider the provider, such as {@code aws}
	 * @param region the region, such as {@code us-east-1}
	 * @param date the day of its prices
	 * @param file the file it was read from
	 * @param sha256 the hash of its content, which a design pins
	 * @param newest whether no snapshot of its provider and region is newer
	 * @param illustrative always {@code false}
	 */
	record Snapshot(String provider, String region, String date, String file, String sha256, boolean newest,
			boolean illustrative) implements PriceSource {

	}

}
