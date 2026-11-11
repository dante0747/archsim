package io.archsim.model.pricing;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonText;

/**
 * The price snapshots this machine has: the ones ArchSim ships, and any in extra folders
 * (the Python engine's {@code ARCHSIM_PRICES_DIR}), extra folders first. A file that is
 * not a valid snapshot is not offered.
 *
 * <p>
 * A document's {@code pricing} names a snapshot by provider and region, and may pin it by
 * date and content hash: then exactly that one is used, never another, or none.
 */
public final class PriceSnapshots {

	/** The provider templates and new documents price with. */
	public static final String DEFAULT_PROVIDER = "aws";

	/** The region templates and new documents price with. */
	public static final String DEFAULT_REGION = "us-east-1";

	private final List<FoundSnapshot> found;

	private PriceSnapshots(List<FoundSnapshot> found) {
		this.found = List.copyOf(found);
	}

	/**
	 * The snapshots ArchSim ships, read once.
	 * @return the shipped snapshots
	 */
	public static PriceSnapshots shipped() {
		return Shipped.SNAPSHOTS;
	}

	/**
	 * The snapshots in these folders ({@code *.json}, by file name), then the shipped
	 * ones.
	 * @param folders the extra folders, in order; one that does not exist is skipped
	 * @return the snapshots of the folders and the shipped ones
	 * @throws UncheckedIOException when a folder cannot be listed
	 */
	public static PriceSnapshots with(List<Path> folders) {
		List<FoundSnapshot> all = new ArrayList<>(SnapshotFiles.inFolders(folders));
		all.addAll(shipped().found);
		return new PriceSnapshots(all);
	}

	/**
	 * The snapshots a document's pricing gets when it pins no date: the newest of each
	 * provider and region.
	 * @return the snapshots, by provider and region, each content once
	 */
	public List<AvailableSnapshot> newest() {
		return SnapshotListing.newest(this.found);
	}

	/**
	 * Every snapshot that can be chosen, each marked whether it is the newest of its
	 * provider and region.
	 * @return the snapshots, by provider and region and newest first, each content once
	 */
	public List<AvailableSnapshot> everyDate() {
		return SnapshotListing.everyDate(this.found);
	}

	/**
	 * The newest snapshot of {@code provider} and {@code region} ({@code generic} needs
	 * none); with {@code date} (and {@code sha256}) exactly that snapshot, never another.
	 * @param provider the provider, or {@code generic} for the illustrative rates
	 * @param region the region; {@code null} for any
	 * @param date the date that pins the snapshot; {@code null} for the newest
	 * @param sha256 the content hash that pins the snapshot; {@code null} for any
	 * @return the snapshot
	 * @throws PricingException naming what this machine has, when there is none
	 */
	public PriceSnapshot load(String provider, @Nullable String region, @Nullable String date,
			@Nullable String sha256) {
		if (PriceSnapshot.GENERIC_PROVIDER.equals(provider)) {
			return PriceSnapshot.GENERIC;
		}
		SnapshotQuery query = new SnapshotQuery(provider, region, date, sha256);
		return this.found.stream()
			.map(FoundSnapshot::snapshot)
			.filter(query::matches)
			.reduce(PriceSnapshots::newerOf)
			.orElseThrow(() -> new PricingException(query.missingMessage(everyDate())));
	}

	/**
	 * The snapshot a document's {@code pricing} JSON names (generic when it has none):
	 * the pinned one when it has {@code snapshotDate} (and {@code snapshotSha256}), else
	 * the newest of its provider and region.
	 * @param pricing the document's {@code pricing}, as JSON; {@code null} when it has
	 * none
	 * @return the snapshot
	 * @throws PricingException when this machine does not have it
	 */
	public PriceSnapshot forPricing(@Nullable JsonNode pricing) {
		JsonNode fields = orEmpty(pricing);
		JsonNode provider = fields.path("provider");
		String providerName = PythonText.truthy(provider) ? PythonText.str(provider) : PriceSnapshot.GENERIC_PROVIDER;
		return load(providerName, text(fields.get("region")), text(fields.get("snapshotDate")),
				text(fields.get("snapshotSha256")));
	}

	/**
	 * {@code pricing} with the snapshot it resolves to written in ({@code region},
	 * {@code snapshotDate}, {@code snapshotSha256}), so a run's input names exactly the
	 * prices it used. Unchanged for generic prices, or when this machine has no such
	 * snapshot.
	 * @param pricing the document's {@code pricing}, as JSON; {@code null} when it has
	 * none
	 * @return a pinned copy, or {@code pricing} itself when there is nothing to pin
	 */
	public @Nullable JsonNode pinned(@Nullable JsonNode pricing) {
		JsonNode fields = orEmpty(pricing);
		JsonNode provider = fields.path("provider");
		if (!fields.isObject() || !PythonText.truthy(provider)
				|| PriceSnapshot.GENERIC_PROVIDER.equals(PythonText.str(provider))) {
			return pricing;
		}
		PriceSnapshot snapshot;
		try {
			snapshot = forPricing(fields);
		}
		catch (PricingException ex) {
			// this machine has no such snapshot: nothing to pin
			return pricing;
		}
		ObjectNode written = ((ObjectNode) fields).deepCopy();
		written.put("region", snapshot.region());
		written.put("snapshotDate", snapshot.snapshotDate());
		written.put("snapshotSha256", snapshot.sha256());
		return written;
	}

	/**
	 * The {@code pricing} templates and new documents start with: the newest shipped
	 * snapshot of {@value #DEFAULT_PROVIDER} {@value #DEFAULT_REGION}, pinned by date;
	 * generic rates when none is shipped.
	 * @return the {@code pricing} JSON
	 */
	public ObjectNode defaultPricing() {
		Optional<String> newest = this.found.stream()
			.filter(FoundSnapshot::shipped)
			.map(FoundSnapshot::snapshot)
			.filter((snapshot) -> snapshot.provider().equals(DEFAULT_PROVIDER)
					&& DEFAULT_REGION.equals(snapshot.region()))
			.map(PriceSnapshots::dateOf)
			.max(Comparator.naturalOrder());
		ObjectNode pricing = JsonNodeFactory.instance.objectNode();
		if (newest.isEmpty()) {
			return pricing.put("provider", PriceSnapshot.GENERIC_PROVIDER);
		}
		return pricing.put("provider", DEFAULT_PROVIDER)
			.put("region", DEFAULT_REGION)
			.put("snapshotDate", newest.get());
	}

	/**
	 * The newer of two snapshots; of two of the same date, the one found first, so that a
	 * snapshot in an extra folder wins over a shipped one.
	 * @param first a snapshot
	 * @param later a snapshot found after it
	 * @return {@code later} when its date is after {@code first}'s, else {@code first}
	 */
	private static PriceSnapshot newerOf(PriceSnapshot first, PriceSnapshot later) {
		return (dateOf(later).compareTo(dateOf(first)) > 0) ? later : first;
	}

	private static String dateOf(PriceSnapshot snapshot) {
		return Objects.requireNonNullElse(snapshot.snapshotDate(), "");
	}

	private static JsonNode orEmpty(@Nullable JsonNode pricing) {
		return (pricing == null || pricing.isNull()) ? JsonNodeFactory.instance.objectNode() : pricing;
	}

	private static @Nullable String text(@Nullable JsonNode value) {
		return (value == null || value.isNull()) ? null : PythonText.str(value);
	}

	/** The shipped snapshots, read the first time they are needed. */
	private static final class Shipped {

		static final PriceSnapshots SNAPSHOTS = new PriceSnapshots(SnapshotFiles.shipped());

	}

}
