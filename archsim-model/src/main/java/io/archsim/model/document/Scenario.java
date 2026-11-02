package io.archsim.model.document;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A named, replayable variant of the design: overrides applied in order to the base
 * document.
 *
 * @param id unique among the scenarios
 * @param name the display name
 * @param description a longer description
 * @param overrides the values it sets, in order
 */
public record Scenario(@Matches(Patterns.ID) String id, @MinLength(1) @MaxLength(120) String name,
		@MaxLength(2000) @Nullable String description, @Default("[]") @MaxItems(100) List<ScenarioOverride> overrides) {

	public Scenario {
		overrides = List.copyOf(overrides);
	}
}
