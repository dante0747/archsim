package io.archsim.model.upgrade;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.archsim.model.text.PythonInts;

/**
 * A document's {@code schemaVersion}, {@code MAJOR.MINOR}, ordered as the Python engine
 * orders it: as a tuple of integers, so {@code 1.10} is newer than {@code 1.7}, and
 * {@code 1.7.0} newer than {@code 1.7}.
 *
 * @param text the version as written
 * @param parts its dot-separated integers
 */
public record SchemaVersion(String text, List<BigInteger> parts) implements Comparable<SchemaVersion> {

	/**
	 * What Python's {@code int()} reads, but for its limit on digits: digits (any
	 * script's), maybe a sign and underscores. The group repeats possessively, so Java
	 * matches it in a loop: a greedy repeat would recurse once a group.
	 */
	private static final Pattern INTEGER = Pattern.compile("[+-]?\\p{Nd}+(?:_\\p{Nd}+)*+");

	/** The base of the digits a version's parts are written in. */
	private static final int DECIMAL = 10;

	public SchemaVersion {
		parts = List.copyOf(parts);
	}

	/**
	 * The version {@code text} names.
	 * @param text the version as written, such as {@code 1.7}
	 * @return the version
	 * @throws UpgradeException when a part is not an integer
	 */
	public static SchemaVersion parse(String text) {
		List<BigInteger> parts = new ArrayList<>();
		for (String part : text.split("\\.", -1)) {
			String trimmed = part.strip();
			if (!INTEGER.matcher(trimmed).matches() || digitCount(trimmed) > PythonInts.MAX_STR_DIGITS) {
				throw new UpgradeException("'%s' is not a valid schemaVersion".formatted(text));
			}
			parts.add(integer(trimmed.replace("_", "")));
		}
		return new SchemaVersion(text, parts);
	}

	private static long digitCount(String part) {
		return part.codePoints().filter(Character::isDigit).count();
	}

	/**
	 * The integer of digits in any script, as Python's {@code int()} reads them.
	 * @param text the digits, maybe after a sign, with no underscores
	 * @return the integer they write
	 */
	private static BigInteger integer(String text) {
		StringBuilder ascii = new StringBuilder(text.length());
		text.codePoints().forEach((codePoint) -> ascii.append(asciiDigit(codePoint)));
		return new BigInteger(ascii.toString());
	}

	/**
	 * A digit of any script as the ASCII digit of the same value.
	 * @param codePoint a character of a version's part
	 * @return the ASCII digit for a digit, the character itself for anything else
	 */
	private static char asciiDigit(int codePoint) {
		return Character.isDigit(codePoint) ? (char) ('0' + Character.digit(codePoint, DECIMAL)) : (char) codePoint;
	}

	/** Python's tuple order: part by part, and a longer tuple after its own prefix. */
	@Override
	public int compareTo(SchemaVersion other) {
		for (int i = 0; i < Math.min(this.parts.size(), other.parts.size()); i++) {
			int order = this.parts.get(i).compareTo(other.parts.get(i));
			if (order != 0) {
				return order;
			}
		}
		return Integer.compare(this.parts.size(), other.parts.size());
	}

	public boolean isNewerThan(SchemaVersion other) {
		return compareTo(other) > 0;
	}

	@Override
	public String toString() {
		return this.text;
	}
}
