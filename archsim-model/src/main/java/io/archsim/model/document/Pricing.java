package io.archsim.model.document;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Const;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.OmitWhenDefault;

/**
 * Where prices come from: {@code generic} illustrative rates, or a price snapshot of a
 * provider and region. A snapshot date (and hash) pins exactly one snapshot, never a
 * newer one.
 *
 * @param provider {@code generic}, or a snapshot's provider such as {@code aws}
 * @param region the snapshot's region
 * @param purchaseOption always {@code on_demand}
 * @param families instance families to snap sizes to; empty: any
 * @param burstable whether a light part may run on a burstable type
 * @param snapshotDate the pinned snapshot's date, {@code YYYY-MM-DD}
 * @param snapshotSha256 the pinned snapshot's content hash
 */
@Description("""
		Where prices come from. ``generic``: ArchSim's illustrative rates, no instance types. Any
		other provider names a price snapshot (docs/pricing.md); auto-sizing then snaps each part to
		the cheapest instance type that covers it (among ``families`` when given) and cost lines cite
		that SKU and the snapshot date. ``snapshotDate`` (and ``snapshotSha256``) pin one snapshot:
		exactly that one is used, never a newer one; a run's input records the snapshot it used.""")
public record Pricing(@Default(GENERIC) @Matches(Patterns.PRICE_NAME) String provider,
		@Matches(Patterns.PRICE_NAME) @Nullable String region,
		@Default("on_demand") @Description(Descriptions.PRICING_PURCHASE_OPTION) @Const("on_demand") String purchaseOption,
		@Default("[]") @Description(Descriptions.PRICING_FAMILIES) @MaxItems(20) List<@Matches(Patterns.FAMILY) String> families,
		@Default("auto") @OmitWhenDefault @Description(Descriptions.PRICING_BURSTABLE) Burstable burstable,
		@OmitWhenDefault @Description(Descriptions.PRICING_SNAPSHOT_DATE) @Matches(Patterns.DATE) @Nullable String snapshotDate,
		@OmitWhenDefault @Description(Descriptions.PRICING_SNAPSHOT_SHA256) @Matches(Patterns.SHA256) @Nullable String snapshotSha256) {

	/** The provider of ArchSim's illustrative rates, which need no snapshot. */
	public static final String GENERIC = "generic";

	/**
	 * Copies the families, so the pricing does not change.
	 */
	public Pricing {
		families = List.copyOf(families);
	}

	/**
	 * Whether prices are the generic illustrative rates rather than a snapshot's.
	 * @return {@code true} for the generic rates
	 */
	public boolean isGeneric() {
		return this.provider.equals(GENERIC);
	}

	/**
	 * Whether one snapshot is pinned, by date or by hash.
	 * @return {@code true} when a date or a hash is given
	 */
	public boolean isPinned() {
		return this.snapshotDate != null || this.snapshotSha256 != null;
	}
}
