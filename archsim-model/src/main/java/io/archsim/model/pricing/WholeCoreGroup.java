package io.archsim.model.pricing;

import java.util.List;

import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.MinLength;

/**
 * Instance families of one provider whose vCPUs are whole cores for the same reason, and
 * the page the reason rests on.
 *
 * @param families the families, such as {@code m7g}, as the price snapshots name them
 * @param reason why a vCPU of these families is a whole core; it names the page that says
 * the rest where the source says only which processor the families run on
 * @param source the provider's page that says so, or that says which processor the
 * families run on
 */
record WholeCoreGroup(@MinItems(1) List<@MinLength(1) String> families, @MinLength(1) String reason,
		@Matches(HTTPS) String source) {

	/** A page anyone can open: an {@code https} address. */
	static final String HTTPS = "^https://\\S+$";

	/**
	 * Copies the families, so the group does not change.
	 */
	WholeCoreGroup {
		families = List.copyOf(families);
	}

}
