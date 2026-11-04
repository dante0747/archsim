package io.archsim.engine.cost;

import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.text.PythonFormat;

/** How the cost estimate writes its numbers, as the Python engine writes them. */
final class CostText {

	private static final double BILLION = 1e9;

	private static final double MILLION = 1e6;

	private static final double TEN_THOUSAND = 1e4;

	private static final double THOUSAND = 1e3;

	private static final double HUNDRED = 100;

	private static final double MIB_PER_GIB = 1024;

	/** A price keeps at least this many decimals. */
	private static final int CENTS = 2;

	private CostText() {
	}

	/**
	 * A quantity: {@code 3.94 B}, {@code 3.94 M}, {@code 12.3 k}, {@code 730},
	 * {@code 0.5}.
	 * @param quantity the quantity
	 * @return how it reads
	 */
	static String quantity(double quantity) {
		if (quantity >= BILLION) {
			return HalfUp.fixed(quantity / BILLION, 2) + " B";
		}
		if (quantity >= MILLION) {
			return HalfUp.fixed(quantity / MILLION, 2) + " M";
		}
		if (quantity >= TEN_THOUSAND) {
			return HalfUp.fixed(quantity / THOUSAND, 1) + " k";
		}
		if (quantity >= HUNDRED) {
			return HalfUp.grouped(quantity);
		}
		return NumberText.plain(HalfUp.round(quantity, 2));
	}

	/**
	 * A price: cents always, more digits only where they are ({@code 3.50},
	 * {@code 0.0225}, {@code 0.0000166667}).
	 * @param price the price
	 * @return how it reads, without the dollar sign
	 */
	static String price(double price) {
		if (price <= 0) {
			return "0.00";
		}
		String text = PythonFormat.format(price, ".10f").replaceAll("0+$", "");
		int decimals = text.length() - text.indexOf('.') - 1;
		return text + "0".repeat(Math.max(0, CENTS - decimals));
	}

	/**
	 * A count with its word, plural unless it is one: {@code 2 replicas},
	 * {@code 1.5 nodes}.
	 * @param count the count
	 * @param word the word for one
	 * @return how it reads
	 */
	static String plural(double count, String word) {
		String shown = (count == Math.rint(count) && Double.isFinite(count)) ? NumberText.plain(count)
				: HalfUp.fixed(count, 1);
		return shown + " " + word + ((count == 1) ? "" : "s");
	}

	/**
	 * A share as a whole percentage, such as {@code 90%}.
	 * @param share the share
	 * @return how it reads
	 */
	static String percent(double share) {
		return NumberText.plain(HalfUp.round(share * HUNDRED, 0)) + "%";
	}

	/**
	 * Memory in megabytes, such as {@code 1,024 MB}.
	 * @param gib the memory, in GiB
	 * @return how it reads
	 */
	static String megabytes(double gib) {
		return HalfUp.grouped(gib * MIB_PER_GIB) + " MB";
	}

}
