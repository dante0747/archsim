package io.archsim.model.pricing;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * The snapshots that can be chosen, as {@link PriceSnapshots#available(boolean)} lists
 * them: each content once, by provider and region, newest first.
 */
final class SnapshotListing {

	/** By provider and region, newest first, then by content hash. */
	private static final Comparator<Key> ORDER = Comparator.comparing(Key::provider)
		.thenComparing(Key::region)
		.thenComparing(Key::date, Comparator.reverseOrder())
		.thenComparing(Key::sha256);

	private SnapshotListing() {
	}

	/**
	 * Every snapshot, each marked whether it is the newest of its provider and region.
	 * @param found the snapshots found, in the order they were found
	 * @return each content once, from the first file that has it, in listing order
	 */
	static List<AvailableSnapshot> everyDate(List<FoundSnapshot> found) {
		Map<Key, String> files = new LinkedHashMap<>();
		for (FoundSnapshot snapshot : found) {
			// the same content in two folders: listed once
			files.putIfAbsent(Key.of(snapshot.snapshot()), snapshot.file());
		}
		Map<List<String>, String> newestDates = newestDates(files.keySet());
		return files.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey(ORDER))
			.map((entry) -> entry.getKey().available(entry.getValue(), newestDates))
			.toList();
	}

	/**
	 * The newest snapshot of each provider and region.
	 * @param found the snapshots found, in the order they were found
	 * @return the newest snapshots, in listing order
	 */
	static List<AvailableSnapshot> newest(List<FoundSnapshot> found) {
		return everyDate(found).stream().filter(AvailableSnapshot::newest).toList();
	}

	private static Map<List<String>, String> newestDates(Collection<Key> keys) {
		Map<List<String>, String> newestDates = new HashMap<>();
		for (Key key : keys) {
			newestDates.merge(key.providerAndRegion(), key.date(), BinaryOperator.maxBy(Comparator.naturalOrder()));
		}
		return newestDates;
	}

	/**
	 * What tells listed snapshots apart; a value a snapshot does not have is empty.
	 *
	 * @param provider the provider
	 * @param region the region
	 * @param date the date
	 * @param sha256 the content hash
	 */
	private record Key(String provider, String region, String date, String sha256) {

		static Key of(PriceSnapshot snapshot) {
			return new Key(snapshot.provider(), Objects.requireNonNullElse(snapshot.region(), ""),
					Objects.requireNonNullElse(snapshot.snapshotDate(), ""),
					Objects.requireNonNullElse(snapshot.sha256(), ""));
		}

		List<String> providerAndRegion() {
			return List.of(this.provider, this.region);
		}

		AvailableSnapshot available(String file, Map<List<String>, String> newestDates) {
			boolean newest = Objects.equals(newestDates.get(providerAndRegion()), this.date);
			return new AvailableSnapshot(this.provider, this.region, this.date, file, this.sha256, newest);
		}

	}

}
