package io.archsim.engine.reporting;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.archsim.engine.technology.PartTechnology;
import io.archsim.model.component.Tier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The note about experimental technologies, which no part the simulator runs so far has:
 * the Python engine's {@code _tier_notes}.
 */
class AssumptionsTests {

	@Test
	void tierNotes_onlyCoreTechnologies_noNote() {
		List<PartTechnology> technologies = List.of(part("Orders", Tier.CORE));

		List<String> notes = Assumptions.tierNotes(technologies);

		assertThat(notes).isEmpty();
	}

	@Test
	void tierNotes_twoExperimentalParts_namesThemWithTheBadgesWords() {
		List<PartTechnology> technologies = List.of(part("Orders", Tier.EXPERIMENTAL), part("Users", Tier.CORE),
				part("Search", Tier.EXPERIMENTAL));

		List<String> notes = Assumptions.tierNotes(technologies);

		assertThat(notes).containsExactly("Experimental technologies (Orders, Search): behaviour based on "
				+ "documentation and illustrative numbers; not yet validated against measurements.");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			5 | Part 1, Part 2, Part 3, Part 4, Part 5
			6 | Part 1, Part 2, Part 3, Part 4, Part 5 and 1 more
			7 | Part 1, Part 2, Part 3, Part 4, Part 5 and 2 more
			""")
	void tierNotes_fiveOrMoreExperimentalParts_namesFiveAndCountsTheRest(int parts, String named) {
		List<PartTechnology> technologies = IntStream.rangeClosed(1, parts)
			.mapToObj((index) -> part("Part " + index, Tier.EXPERIMENTAL))
			.toList();

		List<String> notes = Assumptions.tierNotes(technologies);

		assertThat(notes).containsExactly("Experimental technologies (" + named + "): behaviour based on "
				+ "documentation and illustrative numbers; not yet validated against measurements.");
	}

	private static PartTechnology part(String label, Tier tier) {
		return new PartTechnology(label.toLowerCase(Locale.ROOT), label, "database", "postgres", tier);
	}

}
