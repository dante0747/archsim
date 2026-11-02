package io.archsim.model.document;

import io.archsim.model.codec.Const;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.OmitWhenDefault;
import java.util.List;
import org.jspecify.annotations.Nullable;

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

		@Default("on_demand") @Description("On-demand prices. Savings plans and reserved terms are not modeled yet.") @Const("on_demand") String purchaseOption,

		@Default("[]") @Description("Instance families to snap to (e.g. m7g, r7g); empty: any in the snapshot. Naming a burstable"
				+ " family (t4g) allows it everywhere it fits, without restricting the lists") @MaxItems(20) List<@Matches(Patterns.FAMILY) String> families,

		@Default("auto") @OmitWhenDefault @Description("auto: a light part may run on a burstable type (t4g) when its CPU at the base load stays"
				+ " under 80% of the type's baseline and its peak under 60% of its vCPUs. never: no"
				+ " burstable types (many teams ban them in production).") Burstable burstable,

		@OmitWhenDefault @Description("Use exactly the snapshot of this date (YYYY-MM-DD), never a newer one; a warning and generic"
				+ " rates when this machine does not have it. Empty: the newest.") @Matches(Patterns.DATE) @Nullable String snapshotDate,

		@OmitWhenDefault @Description("With snapshotDate: the snapshot's content hash, so changed prices of the same date are not"
				+ " used either. A run's input records it.") @Matches(Patterns.SHA256) @Nullable String snapshotSha256) {

	/** The provider of ArchSim's illustrative rates, which need no snapshot. */
	public static final String GENERIC = "generic";

	public Pricing {
		families = List.copyOf(families);
	}

	/** Whether prices are the generic illustrative rates rather than a snapshot's. */
	public boolean isGeneric() {
		return provider.equals(GENERIC);
	}

	/** Whether one snapshot is pinned, by date or by hash. */
	public boolean isPinned() {
		return snapshotDate != null || snapshotSha256 != null;
	}
}
