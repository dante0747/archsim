package io.archsim.model.pricing;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * The snapshot a document's pricing asks for: a provider, and perhaps a region, a date
 * and a content hash. A date or a hash pins exactly one snapshot.
 *
 * @param provider the provider
 * @param region the region; {@code null} for any
 * @param date the date; {@code null} for any
 * @param sha256 the content hash; {@code null} for any
 */
record SnapshotQuery(String provider, @Nullable String region, @Nullable String date, @Nullable String sha256) {

	/** How many characters of a content hash a message shows. */
	private static final int SHORT_HASH_LENGTH = 12;

	/**
	 * Whether a snapshot is one the query asks for.
	 * @param snapshot the snapshot to test
	 * @return {@code true} when it has the provider and every value the query gives
	 */
	boolean matches(PriceSnapshot snapshot) {
		return snapshot.provider().equals(this.provider) && accepts(this.region, snapshot.region())
				&& accepts(this.date, snapshot.snapshotDate()) && accepts(this.sha256, snapshot.sha256());
	}

	/**
	 * Why no snapshot matches: what was asked for, and what this machine has instead.
	 * @param everyDate every snapshot that can be chosen, as
	 * {@link PriceSnapshots#everyDate()} lists them
	 * @return the message
	 */
	String missingMessage(List<AvailableSnapshot> everyDate) {
		String wanted = wanted();
		List<AvailableSnapshot> sameRegion = everyDate.stream().filter(this::isOfProviderAndRegion).toList();
		if (isPinned() && !sameRegion.isEmpty()) {
			return "%s (this machine has %s %s); a pinned snapshot is never swapped for another.".formatted(wanted,
					this.provider, datesAndHashes(sameRegion));
		}
		String have = everyDate.stream()
			.filter(AvailableSnapshot::newest)
			.map((snapshot) -> snapshot.provider() + " " + snapshot.region())
			.collect(Collectors.joining(", "));
		return "%s (available: generic; snapshots: %s).".formatted(wanted, have.isEmpty() ? "none" : have);
	}

	private String wanted() {
		String wanted = "no price snapshot for %s %s"
			.formatted(this.provider, Objects.requireNonNullElse(this.region, ""))
			.stripTrailing();
		if (this.date != null) {
			wanted += " of " + this.date;
		}
		if (this.sha256 != null) {
			wanted += " with content " + this.sha256.substring(0, Math.min(SHORT_HASH_LENGTH, this.sha256.length()));
		}
		return wanted;
	}

	private boolean isPinned() {
		return this.date != null || this.sha256 != null;
	}

	private boolean isOfProviderAndRegion(AvailableSnapshot snapshot) {
		return snapshot.provider().equals(this.provider)
				&& (this.region == null || snapshot.region().equals(this.region));
	}

	private static String datesAndHashes(List<AvailableSnapshot> snapshots) {
		return snapshots.stream()
			.map((snapshot) -> "%s %s (%s)".formatted(snapshot.region(), snapshot.date(),
					snapshot.sha256().substring(0, SHORT_HASH_LENGTH)))
			.collect(Collectors.joining(", "));
	}

	private static boolean accepts(@Nullable String wanted, @Nullable String actual) {
		return wanted == null || wanted.equals(actual);
	}

}
