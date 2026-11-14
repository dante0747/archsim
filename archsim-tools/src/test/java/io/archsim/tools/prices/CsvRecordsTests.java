package io.archsim.tools.prices;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class CsvRecordsTests {

	/**
	 * Quoted commas and quotes, a line break in quotes, text after a closing quote, a
	 * quote in an unquoted field, a blank line, CRLF, CR and LF line breaks, and a quote
	 * never closed; {@code csv-tricky.json} is what Python's {@code csv.reader} read from
	 * it.
	 */
	private static final String TRICKY = "a,\"b,c\",\"d\"\"e\"\r\n\"multi\nline\",\"x\"y,z\n\nab\"c,\"q\"after,\r"
			+ "\"unterminated,end";

	@Test
	void next_aTrickyText_readsItAsPythonsCsvModuleDid() throws IOException {
		CsvRecords records = new CsvRecords(new StringReader(TRICKY));

		List<List<String>> read = new ArrayList<>();
		for (Optional<List<String>> record = records.next(); record.isPresent(); record = records.next()) {
			read.add(record.get());
		}

		assertThat(read).isEqualTo(python(Recorded.json("csv-tricky.json")));
	}

	@Test
	void next_theEndOfTheText_isNoRecord() throws IOException {
		CsvRecords records = new CsvRecords(new StringReader("a,b\n"));

		records.next();

		assertThat(records.next()).isEmpty();
	}

	private static List<List<String>> python(JsonNode records) {
		List<List<String>> lists = new ArrayList<>();
		for (JsonNode record : records) {
			List<String> fields = new ArrayList<>();
			record.forEach((field) -> fields.add(field.stringValue()));
			lists.add(fields);
		}
		return lists;
	}

}
