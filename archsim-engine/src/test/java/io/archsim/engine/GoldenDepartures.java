package io.archsim.engine;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.parity.GoldenEntry;
import io.archsim.parity.PythonError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The golden entries the Java does not match word for word, each with why, and what it
 * still matches: the same kind of error, and its message up to where the Python's and the
 * Java's libraries word it differently. Every other entry of a ported function must match
 * exactly.
 */
final class GoldenDepartures {

	private static final Map<Recorded, Departure> DEPARTURES = Map.of(
			new Recorded("fixtures/importers", "/cases/compose-bad-yaml/entries/import_design"),
			new Departure("not valid YAML: while parsing a flow sequence\n",
					"SnakeYAML marks where the problem is in its own words, not PyYAML's (ADR-0043)"));

	private GoldenDepartures() {
	}

	/**
	 * The departure of an entry.
	 * @param entry a golden entry
	 * @return how the Java departs from it; empty when it must match exactly
	 */
	static Optional<Departure> of(GoldenEntry entry) {
		return Optional.ofNullable(DEPARTURES.get(Recorded.of(entry)));
	}

	/**
	 * Every entry listed.
	 * @return each entry's file and pointer
	 */
	static Set<Recorded> entries() {
		return DEPARTURES.keySet();
	}

	/**
	 * Where a golden file records a call.
	 *
	 * @param file the golden file, such as {@code fixtures/importers}
	 * @param pointer the entry's place in it
	 */
	record Recorded(String file, String pointer) {

		static Recorded of(GoldenEntry entry) {
			return new Recorded(entry.file(), entry.pointer());
		}

	}

	/**
	 * How the Java departs from an entry that raised.
	 *
	 * @param prefix where the messages agree: up to where the two libraries' words part
	 * @param why why the rest differs
	 */
	record Departure(String prefix, String why) {

		/**
		 * Checks the Java's error against the entry's: the same type, both messages
		 * starting with the prefix, and differing past it (else the entry is no departure
		 * and leaves the list).
		 * @param entry the entry
		 * @param call the Java counterpart of its function
		 */
		void check(GoldenEntry entry, Function<GoldenEntry, JsonNode> call) {
			try {
				JsonNode actual = call.apply(entry);
				fail("%s: the Python engine raised %s, Java returned %s", entry, entry.expectedError(), actual);
			}
			catch (PythonError ex) {
				JsonNode expected = entry.expectedError();
				assertThat(ex.error().path("type")).as("%s: the error's type", entry).isEqualTo(expected.path("type"));
				assertThat(expected.path("message").stringValue()).as("%s: the Python engine's message", entry)
					.startsWith(this.prefix);
				assertThat(ex.error().path("message").stringValue()).as("%s: the Java's message (%s)", entry, this.why)
					.startsWith(this.prefix)
					.isNotEqualTo(expected.path("message").stringValue());
			}
		}

	}

}
