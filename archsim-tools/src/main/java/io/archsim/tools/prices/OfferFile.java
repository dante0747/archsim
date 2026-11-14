package io.archsim.tools.prices;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Stream;

/**
 * An AWS offer file in CSV: lines of metadata ({@code FormatVersion}, {@code Disclaimer},
 * {@code Version} and the like), a header row that starts with {@code SKU}, then a row
 * per price. The EC2 file is several hundred MB, so its rows are streamed, never held.
 */
final class OfferFile {

	/** How many metadata lines the header is read from, at most. */
	private static final int MAX_METADATA = 20;

	private OfferFile() {
	}

	/**
	 * The rows of an offer file, streamed; closing the stream closes the file.
	 * @param file the file, in UTF-8
	 * @return its rows, each with the header's columns
	 * @throws IOException when it cannot be opened or read up to its header row
	 * @throws PriceListException when it has no header row
	 */
	static Stream<OfferRow> rows(Path file) throws IOException {
		BufferedReader text = Files.newBufferedReader(file, StandardCharsets.UTF_8);
		try {
			return rows(text).onClose(() -> close(text));
		}
		catch (UncheckedIOException | PriceListException ex) {
			close(text);
			throw ex;
		}
	}

	/**
	 * The rows of an offer file's text, past its metadata: each with the header's
	 * columns, a row of another length skipped, as {@code aws.py} read them.
	 * @param text the text
	 * @return its rows, read as the stream is
	 * @throws PriceListException when it has no header row
	 * @throws UncheckedIOException when it cannot be read
	 */
	static Stream<OfferRow> rows(BufferedReader text) {
		OfferRow.Columns columns = OfferRow.Columns.of(headerRow(text));
		CsvRecords records = new CsvRecords(text);
		return Stream.iterate(next(records), Optional::isPresent, (last) -> next(records))
			.map(Optional::orElseThrow)
			.filter((values) -> values.size() == columns.size())
			.map((values) -> new OfferRow(columns, values));
	}

	/**
	 * The metadata before an offer file's header row: its first fields by name, from at
	 * most twenty lines.
	 * @param text the file's text
	 * @return the metadata, in the file's order
	 * @throws UncheckedIOException when it cannot be read
	 */
	static SequencedMap<String, String> header(BufferedReader text) {
		SequencedMap<String, String> metadata = new LinkedHashMap<>();
		CsvRecords records = new CsvRecords(text);
		for (Optional<List<String>> record = next(records); record.isPresent(); record = next(records)) {
			List<String> fields = record.get();
			if (fields.isEmpty() || "SKU".equals(fields.getFirst()) || metadata.size() >= MAX_METADATA) {
				break;
			}
			if (fields.size() >= 2) {
				metadata.put(fields.get(0), fields.get(1));
			}
		}
		return metadata;
	}

	// the header row: the first line that starts with SKU, read on its own
	private static List<String> headerRow(BufferedReader text) {
		try {
			for (String line = text.readLine(); line != null; line = text.readLine()) {
				if (line.startsWith("\"SKU\"") || line.startsWith("SKU,")) {
					return new CsvRecords(new StringReader(line)).next().orElseThrow();
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		throw new PriceListException("no header row (SKU, ...) found: the price list format may have changed");
	}

	private static Optional<List<String>> next(CsvRecords records) {
		try {
			return records.next();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void close(BufferedReader text) {
		try {
			text.close();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
