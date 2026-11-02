package io.archsim.model.upgrade;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
	 * What Python's {@code int()} accepts: digits (any script's), maybe a sign and
	 * underscores.
	 */
	private static final Pattern INTEGER = Pattern.compile("[+-]?\\p{Nd}+(?:_\\p{Nd}+)*");

	public SchemaVersion {
		parts = List.copyOf(parts);
	}

	/**
	 * The version {@code text} names.
	 * @throws UpgradeException when a part is not an integer
	 */
	public static SchemaVersion parse(String text) {
		List<BigInteger> parts = new ArrayList<>();
		for (String part : text.split("\\.", -1)) {
			String trimmed = part.strip();
			if (!INTEGER.matcher(trimmed).matches()) {
				throw new UpgradeException("'%s' is not a valid schemaVersion".formatted(text));
			}
			parts.add(integer(trimmed.replace("_", "")));
		}
		return new SchemaVersion(text, parts);
	}

	/** The integer of digits in any script, as Python's {@code int()} reads them. */
	private static BigInteger integer(String text) {
		StringBuilder ascii = new StringBuilder(text.length());
		text.codePoints()
			.forEach(c -> ascii.append(Character.isDigit(c) ? (char) ('0' + Character.digit(c, 10)) : (char) c));
		return new BigInteger(ascii.toString());
	}

	/** Python's tuple order: part by part, and a longer tuple after its own prefix. */
	@Override
	public int compareTo(SchemaVersion other) {
		for (int i = 0; i < Math.min(parts.size(), other.parts.size()); i++) {
			int c = parts.get(i).compareTo(other.parts.get(i));
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(parts.size(), other.parts.size());
	}

	public boolean isNewerThan(SchemaVersion other) {
		return compareTo(other) > 0;
	}

	@Override
	public String toString() {
		return text;
	}
}
