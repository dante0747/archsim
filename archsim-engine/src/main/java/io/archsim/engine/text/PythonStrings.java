package io.archsim.engine.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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

	/**
	 * What Python 3.12's {@code str.isspace()} counts as whitespace (Unicode 15.0).
	 * Java's {@link Character#isWhitespace} counts neither the next-line character
	 * (U+0085) nor the no-break spaces (U+00A0, U+2007, U+202F); Python counts all four.
	 */
	private static final String WHITESPACE = "\t\n\u000b\f\r\u001c\u001d\u001e\u001f \u0085\u00a0\u1680"
			+ "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000";

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
	 * A text split at runs of whitespace, as Python's {@code str.split()} with no
	 * argument splits it: at what {@code str.isspace()} counts as whitespace, with no
	 * empty words.
	 * @param text a text
	 * @return its words, in order
	 */
	public static List<String> words(String text) {
		List<String> words = new ArrayList<>();
		StringBuilder word = new StringBuilder();
		text.codePoints().forEach((codePoint) -> {
			if (WHITESPACE.indexOf(codePoint) < 0) {
				word.appendCodePoint(codePoint);
			}
			else if (!word.isEmpty()) {
				words.add(word.toString());
				word.setLength(0);
			}
		});
		if (!word.isEmpty()) {
			words.add(word.toString());
		}
		return List.copyOf(words);
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
