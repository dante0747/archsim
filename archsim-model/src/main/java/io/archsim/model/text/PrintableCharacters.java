package io.archsim.model.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Which characters Python 3.12's {@code str.isprintable()} accepts, and so which its
 * {@code repr()} writes as they are: all but the Unicode categories Cc, Cf, Cs, Co, Cn,
 * Zl, Zp and Zs, the space excepted, as Python's Unicode 15.0 database assigns them.
 * Java's own categories follow its newer Unicode version, so the rejected code points are
 * read from the table {@code scripts/export_printable.py} writes from Python.
 */
final class PrintableCharacters {

	private static final String TABLE = "nonprintable.tsv";

	private static final int HEX = 16;

	private static final Runs REJECTED = Runs.read();

	private PrintableCharacters() {
	}

	/**
	 * Whether Python prints a character as it is.
	 * @param codePoint the character, or a surrogate on its own
	 * @return whether {@code str.isprintable()} accepts it
	 */
	static boolean isPrintable(int codePoint) {
		return !REJECTED.contains(codePoint);
	}

	/**
	 * Runs of code points, each from its first to its last, in order and apart.
	 */
	private static final class Runs {

		private final int[] firsts;

		private final int[] lasts;

		private Runs(int[] firsts, int[] lasts) {
			this.firsts = firsts;
			this.lasts = lasts;
		}

		/**
		 * Whether a run holds a code point.
		 * @param codePoint the code point
		 * @return whether one does
		 */
		boolean contains(int codePoint) {
			int found = Arrays.binarySearch(this.firsts, codePoint);
			// the last run that starts at the code point or before it
			int run = (found >= 0) ? found : -found - 2;
			return run >= 0 && codePoint <= this.lasts[run];
		}

		/**
		 * Reads the table: comment lines, then the first and last code point of a run per
		 * line, in hex.
		 * @return the runs
		 */
		static Runs read() {
			try (InputStream in = Objects.requireNonNull(PrintableCharacters.class.getResourceAsStream(TABLE), TABLE);
					BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				List<String[]> lines = reader.lines()
					.filter((line) -> !line.startsWith("#"))
					.map((line) -> line.split("\t", -1))
					.toList();
				int[] firsts = lines.stream().mapToInt((columns) -> Integer.parseInt(columns[0], HEX)).toArray();
				int[] lasts = lines.stream().mapToInt((columns) -> Integer.parseInt(columns[1], HEX)).toArray();
				return new Runs(firsts, lasts);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

	}

}
