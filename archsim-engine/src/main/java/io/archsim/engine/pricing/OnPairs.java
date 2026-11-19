package io.archsim.engine.pricing;

import org.jspecify.annotations.Nullable;

import io.archsim.model.component.Resources;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.WholeCoreFamilies;

/**
 * What a part needs of an instance type whose vCPUs it runs as hyperthread pairs, two per
 * physical core (ADR-0091): more vCPU than on whole cores for the same work, since a pair
 * with both busy does less than two cores' work.
 *
 * @param vcpu the vCPU it needs on a type of 2 or more vCPU that it runs as pairs
 * @param wholeCoresOf the provider whose whole-core families' types it runs as whole
 * cores, as the default counts them; {@code null}: it runs every type of 2 or more vCPU
 * as pairs, as a sibling speed set on the part or the design says
 */
public record OnPairs(double vcpu, @Nullable String wholeCoresOf) {

	/**
	 * Whether the part runs an instance type's vCPUs as pairs: a type of 2 or more vCPU,
	 * unless it is of a whole-core family and the part goes by the default.
	 * @param sku the instance type
	 * @return whether its vCPUs are pairs to the part
	 */
	public boolean runsAsPairs(Sku sku) {
		if (sku.vcpu() < Resources.FEWEST_PAIRED_VCPU) {
			return false;
		}
		return this.wholeCoresOf == null || !WholeCoreFamilies.has(this.wholeCoresOf, sku.family());
	}

}
