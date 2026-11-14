package io.archsim.tools.prices;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CSV records as Python's {@code csv.reader} reads them in its default dialect: fields
 * separated by commas, a field in double quotes holding commas, line breaks and doubled
 * quotes, text after a closing quote kept, and a blank line read as a record of no
 * fields. A record ends at a line break outside quotes: {@code \n}, {@code \r} or
 * {@code \r\n}.
 */
final class CsvRecords {

	private static final int END = -1;

	private static final char QUOTE = '"';

	private static final char COMMA = ',';

	private final Reader reader;

	private int pushedBack = END;

	/**
	 * Records of a text.
	 * @param reader the text, read from where it stands
	 */
	CsvRecords(Reader reader) {
		this.reader = reader;
	}

	/**
	 * The next record.
	 * @return its fields, none for a blank line; empty at the end of the text
	 * @throws IOException when the text cannot be read
	 */
	Optional<List<String>> next() throws IOException {
		int first = read();
		if (first == END) {
			return Optional.empty();
		}
		List<String> fields = new ArrayList<>();
		if (isLineBreak(first)) {
			skipLineFeedAfter(first);
			return Optional.of(fields);
		}
		this.pushedBack = first;
		boolean more = true;
		while (more) {
			more = field(fields);
		}
		return Optional.of(fields);
	}

	// one field, and whether another follows it in the record
	private boolean field(List<String> fields) throws IOException {
		StringBuilder field = new StringBuilder();
		int next = read();
		if (next == QUOTE) {
			next = quoted(field);
		}
		while (next != COMMA && next != END && !isLineBreak(next)) {
			field.append((char) next);
			next = read();
		}
		fields.add(field.toString());
		skipLineFeedAfter(next);
		return next == COMMA;
	}

	// a quoted text up to its closing quote, a doubled quote read as one; then the
	// character after the closing quote, or the end of the text when it never closes
	private int quoted(StringBuilder field) throws IOException {
		while (true) {
			int next = read();
			if (next == END) {
				return END;
			}
			if (next == QUOTE) {
				int after = read();
				if (after != QUOTE) {
					return after;
				}
			}
			field.append((char) next);
		}
	}

	private void skipLineFeedAfter(int character) throws IOException {
		if (character == '\r') {
			int next = read();
			if (next != '\n') {
				this.pushedBack = next;
			}
		}
	}

	private int read() throws IOException {
		if (this.pushedBack != END) {
			int character = this.pushedBack;
			this.pushedBack = END;
			return character;
		}
		return this.reader.read();
	}

	private static boolean isLineBreak(int character) {
		return character == '\n' || character == '\r';
	}

}
