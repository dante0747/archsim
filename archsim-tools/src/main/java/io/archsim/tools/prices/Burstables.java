package io.archsim.tools.prices;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * AWS's burstable types (t3, t4g, db.t4g, cache.t4g, kafka.t3), which run on CPU credits
 * rather than full cores, and the baseline CPU AWS documents for some of them. The price
 * list does not carry the baseline: it comes from AWS's burstable performance instance
 * tables, where a t4g.large's two vCPUs can each run at 30% for ever and spend CPU
 * credits above that. Other burstable families (t2, and newer ones) get none, so that
 * they are never picked for a light part.
 */
final class Burstables {

	/** A burstable type's name; any decimal digit after its {@code t}, as Python's. */
	private static final Pattern BURSTABLE = Pattern.compile("(db\\.|cache\\.|kafka\\.)?t\\d",
			Pattern.UNICODE_CHARACTER_CLASS);

	/**
	 * The baseline CPU per vCPU of the t3, t3a and t4g sizes, and their db. and cache.
	 * ones.
	 */
	private static final Map<String, Double> BASELINE_CPU = Map.of("nano", 0.05, "micro", 0.10, "small", 0.20, "medium",
			0.20, "large", 0.30, "xlarge", 0.40, "2xlarge", 0.40);

	private static final Set<String> BASELINE_FAMILIES = Set.of("t3", "t3a", "t4g");

	private Burstables() {
	}

	/**
	 * Whether a type runs on CPU credits.
	 * @param type the type, such as {@code t4g.large} or {@code db.t4g.micro}
	 * @return whether it is burstable
	 */
	static boolean isBurstable(String type) {
		return BURSTABLE.matcher(type).lookingAt();
	}

	/**
	 * The documented baseline CPU per vCPU of a burstable type.
	 * @param type the type, such as {@code t4g.large}, {@code db.t4g.micro} or
	 * {@code cache.t3.small}
	 * @return the baseline; {@code null} when it is not burstable or its family is not in
	 * AWS's tables
	 */
	static @Nullable Double baselineCpu(String type) {
		String name = type;
		for (String prefix : new String[] { "db.", "cache.", "kafka." }) {
			name = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
		}
		int dot = name.indexOf('.');
		String family = (dot < 0) ? name : name.substring(0, dot);
		String size = (dot < 0) ? "" : name.substring(dot + 1);
		if (!isBurstable(type) || !BASELINE_FAMILIES.contains(family)) {
			return null;
		}
		return BASELINE_CPU.get(size);
	}

	/**
	 * A type with the price of the surplus CPU credits its family spends, when it is
	 * burstable and the price is known and not zero.
	 * @param type the type
	 * @param creditVcpuHour its family's credit price per vCPU-hour; {@code null} when
	 * unknown
	 * @return the type, with the price or as it was
	 */
	static Sku withCredit(Sku type, @Nullable Double creditVcpuHour) {
		if (creditVcpuHour == null || creditVcpuHour == 0 || !type.burst().burstable()) {
			return type;
		}
		return type.withCredit(creditVcpuHour);
	}

	/**
	 * The burstable family a {@code CPU Credits} row prices: {@code CPUCredits:t4g} and
	 * {@code CPUCredits:db.t4g} both price t4g.
	 * @param row the row
	 * @return the family
	 */
	static String creditFamily(OfferRow row) {
		String usage = row.usage();
		int colon = usage.indexOf(':');
		String family = (colon < 0) ? "" : usage.substring(colon + 1);
		family = family.startsWith("db.") ? family.substring("db.".length()) : family;
		return family.startsWith("cache.") ? family.substring("cache.".length()) : family;
	}

}
