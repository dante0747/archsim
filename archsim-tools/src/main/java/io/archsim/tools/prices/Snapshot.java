package io.archsim.tools.prices;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * A price snapshot before it is written: where its prices are from, its types by list
 * and, for AWS, its per-unit rates.
 *
 * @param provider the provider: {@code aws}, {@code azure} or {@code gcp}
 * @param region the region, in the provider's own words
 * @param sources the price lists it was read from
 * @param note how it was read
 * @param lists the types by list ({@link SnapshotWriter#LISTS}), in the order they were
 * read; a list not given is written empty
 * @param rates the per-unit rates (storage per GiB-month, requests per million and the
 * like) when the provider's snapshot has any, in the order they were read
 */
record Snapshot(String provider, String region, List<Source> sources, String note,
		SequencedMap<String, List<Sku>> lists, Optional<SequencedMap<String, Double>> rates) {

	Snapshot {
		sources = List.copyOf(sources);
		if (!SnapshotWriter.LISTS.containsAll(lists.keySet())) {
			throw new IllegalArgumentException("lists " + lists.keySet() + " beyond " + SnapshotWriter.LISTS);
		}
		lists = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(lists));
		rates = rates.map((given) -> Collections.unmodifiableSequencedMap(new LinkedHashMap<>(given)));
	}

	/**
	 * A snapshot of compute types alone, as Azure's and GCP's are.
	 * @param where the provider and region
	 * @param source the price list it was read from
	 * @param note how it was read
	 * @param compute the compute types
	 * @return the snapshot
	 */
	static Snapshot ofCompute(Where where, Source source, String note, List<Sku> compute) {
		SequencedMap<String, List<Sku>> lists = new LinkedHashMap<>();
		lists.put("compute", compute);
		return new Snapshot(where.provider(), where.region(), List.of(source), note, lists, Optional.empty());
	}

	/**
	 * A price list a snapshot was read from.
	 *
	 * @param name its name, such as {@code Azure Retail Prices API}
	 * @param url where it is
	 */
	record Source(String name, String url) {

	}

	/**
	 * Whose prices a snapshot holds.
	 *
	 * @param provider the provider: {@code aws}, {@code azure} or {@code gcp}
	 * @param region the region, in the provider's own words
	 */
	record Where(String provider, String region) {

	}

}
