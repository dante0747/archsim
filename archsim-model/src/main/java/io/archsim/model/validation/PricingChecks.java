package io.archsim.model.validation;

import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Pricing;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.pricing.PricingException;

/**
 * Checks a design's pricing against its price snapshot: that the snapshot is on this
 * machine, and that the instance families and types the design names are in it. Generic
 * pricing needs no snapshot, so it is not checked.
 */
final class PricingChecks {

	/** The most instance families a hint lists. */
	private static final int FAMILIES_LISTED = 20;

	private final ValidationContext context;

	private final PriceSnapshots prices;

	/**
	 * Checks against these snapshots.
	 * @param context the validation so far
	 * @param prices the price snapshots on this machine
	 */
	PricingChecks(ValidationContext context, PriceSnapshots prices) {
		this.context = context;
		this.prices = prices;
	}

	void check() {
		Pricing pricing = this.context.document().pricing();
		if (pricing.isGeneric()) {
			return;
		}
		PriceSnapshot snapshot;
		try {
			snapshot = this.prices.forPricing(RecordCodec.write(pricing));
		}
		catch (PricingException ex) {
			this.context.report(snapshotMissing(pricing, ex));
			return;
		}
		checkFamilies(pricing, snapshot);
		if (snapshot.hasShapes()) {
			checkInstanceTypes(snapshot);
		}
	}

	private static Issue snapshotMissing(Pricing pricing, PricingException missing) {
		return Issue
			.warning("pricing.snapshot_missing",
					"%s Sizes are not snapped and costs use generic illustrative rates."
						.formatted(missing.getMessage()))
			.withHint(isPinned(pricing)
					? "Add that snapshot file, or choose another snapshot (clearing snapshotDate uses the newest)."
					: "Add a price snapshot for that provider on this machine, or use generic rates.");
	}

	/**
	 * Whether the pricing names one snapshot, by its date or its content hash.
	 * @param pricing the design's pricing
	 * @return {@code true} when a snapshot is pinned
	 */
	private static boolean isPinned(Pricing pricing) {
		if (pricing.snapshotDate() != null) {
			return true;
		}
		String sha256 = pricing.snapshotSha256();
		return sha256 != null && !sha256.isEmpty();
	}

	private void checkFamilies(Pricing pricing, PriceSnapshot snapshot) {
		List<String> unknown = snapshot.unknownFamilies(pricing.families());
		if (unknown.isEmpty()) {
			return;
		}
		List<String> known = List.copyOf(snapshot.families());
		String some = String.join(", ", known.subList(0, Math.min(FAMILIES_LISTED, known.size())));
		this.context.report(Issue
			.warning("pricing.unknown_family",
					"%s: no instance family of that name in %s, so it restricts nothing."
						.formatted(String.join(", ", unknown), snapshot.label()))
			.atPath("pricing.families")
			.withHint("Families in it: %s.".formatted(some.isEmpty() ? "none" : some)));
	}

	private void checkInstanceTypes(PriceSnapshot snapshot) {
		Set<String> listed = snapshot.instanceTypes();
		for (NodeSpec node : this.context.declaredNodes()) {
			JsonNode named = node.config().get("instanceType");
			if (named != null && named.isString() && !named.stringValue().isEmpty()
					&& !listed.contains(named.stringValue())) {
				this.context.report(Issue
					.warning("pricing.unknown_instance_type",
							("%s: no instance type %s in %s, so it runs on the cheapest type that covers its CPU and"
									+ " memory.")
								.formatted(node.name(), named.stringValue(), snapshot.label()))
					.atNode(node.id())
					.atPath("instanceType")
					.withHint("Choose a type from the snapshot, or clear it."));
			}
		}
	}

}
