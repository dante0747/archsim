package io.archsim.tools.prices;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.engine.text.PythonStrings;

/**
 * The text of an offer file's cells, read as {@code aws.py} read it: memory in GiB, whole
 * vCPUs, and a type's name in parts.
 */
final class OfferText {

	private OfferText() {
	}

	/**
	 * Memory in GiB: {@code 8 GiB}, {@code 6.38 GiB}, {@code 16 GB}, {@code 1,024 GiB}
	 * (GB in price lists means GiB).
	 * @param text the cell
	 * @return the GiB; {@code null} when its first word is not a number
	 */
	static @Nullable Double gib(String text) {
		String stripped = PythonStrings.strip(text.replace(",", ""));
		int space = stripped.indexOf(' ');
		String number = (space < 0) ? stripped : stripped.substring(0, space);
		try {
			return PythonNumbers.parseFloat(number);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * Whether a cell is a whole number of vCPUs: one or more decimal digits, of any
	 * script. Python's {@code str.isdigit()} also took digits such as superscripts, which
	 * its {@code float()} then refused; such a row is skipped here.
	 * @param text the cell
	 * @return whether it is digits
	 */
	static boolean isDigits(String text) {
		return !text.isEmpty() && text.codePoints().allMatch(Character::isDigit);
	}

	/**
	 * The vCPUs of a cell of digits.
	 * @param digits the cell, digits as {@link #isDigits} accepts them
	 * @return its value
	 */
	static double vcpu(String digits) {
		return PythonNumbers.parseFloat(digits);
	}

	/**
	 * A part of a type's name between dots: {@code db.m7g.large}'s part 1 is {@code m7g}.
	 * @param type the type
	 * @param index the part
	 * @return the part
	 * @throws PriceListException when the name has no such part
	 */
	static String part(String type, int index) {
		List<String> parts = List.of(type.split("\\.", -1));
		if (index >= parts.size()) {
			throw new PriceListException("the type " + type + " has no family: the price list format may have changed");
		}
		return parts.get(index);
	}

}
