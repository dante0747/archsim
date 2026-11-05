package io.archsim.engine.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Texts compared as the Python engine compares them: folded with {@code str.casefold()},
 * and ordered by code point, where Java's {@link String#compareTo} orders UTF-16 units.
 */
public final class PythonStrings {

	/**
	 * What each code point folds to, when not to itself: Python 3.12's case folding
	 * (Unicode 15.0), read from data, since the JDK's case mappings follow a newer
	 * Unicode.
	 */
	private static final Map<Integer, String> FOLDS = folds();

	private static final int HEX = 16;

	private PythonStrings() {
	}

	/**
	 * A text folded as Python's {@code str.casefold()} folds it, one character at a time.
	 * @param text a text
	 * @return its folded text
	 */
	public static String casefold(String text) {
		StringBuilder folded = new StringBuilder(text.length());
		text.codePoints().forEach((codePoint) -> {
			String fold = FOLDS.get(codePoint);
			if (fold != null) {
				folded.append(fold);
			}
			else {
				folded.appendCodePoint(codePoint);
			}
		});
		return folded.toString();
	}

	/**
	 * Python's order of two texts: by code point, a prefix first.
	 * @param first a text
	 * @param second another text
	 * @return negative, zero or positive as the first comes before, with or after the
	 * second
	 */
	public static int compare(String first, String second) {
		return Arrays.compare(first.codePoints().toArray(), second.codePoints().toArray());
	}

	/**
	 * Reads the folding table {@code scripts/export_casefold.py} writes: a code point and
	 * what it folds to per line, in hex, after comment lines.
	 * @return what each code point listed folds to
	 */
	private static Map<Integer, String> folds() {
		try (InputStream in = Objects.requireNonNull(PythonStrings.class.getResourceAsStream("casefold.tsv"),
				"casefold.tsv");
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			Map<Integer, String> folds = new HashMap<>();
			reader.lines().filter((line) -> !line.startsWith("#")).forEach((line) -> {
				String[] columns = line.split("\t", -1);
				folds.put(Integer.parseInt(columns[0], HEX),
						Arrays.stream(columns[1].split(" ", -1))
							.map((hex) -> Character.toString(Integer.parseInt(hex, HEX)))
							.collect(Collectors.joining()));
			});
			return Map.copyOf(folds);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
