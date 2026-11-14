package io.archsim.tools.prices;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class PriceDiffTests {

	// prices moved, types came and went, rates changed; "many" shows 25 of 37 moves
	@ParameterizedTest
	@CsvSource({ "small, diff-small-new.json", "many, diff-new.json" })
	void summarize_changedPrices_saysWhatScriptsPricesSaid(String name, String current) {
		PriceDiff.Summary summary = PriceDiff.summarize(Optional.of(Recorded.json("diff-old.json")),
				Recorded.json(current));

		assertThat(summary.text()).isEqualTo(Recorded.text("diff-" + name + ".md"));
		assertThat(summary.changed()).isTrue();
	}

	@Test
	void summarize_noEarlierSnapshot_saysThereIsNoneToCompareWith() {
		PriceDiff.Summary summary = PriceDiff.summarize(Optional.empty(), Recorded.json("diff-new.json"));

		assertThat(summary.text()).isEqualTo(Recorded.text("diff-first.md"));
		assertThat(summary.changed()).isTrue();
	}

	@Test
	void summarize_onlyTheDateMoved_saysNoPriceChanged() {
		ObjectNode current = (ObjectNode) Recorded.json("diff-old.json");
		current.put("snapshotDate", "2026-11-01");

		PriceDiff.Summary summary = PriceDiff.summarize(Optional.of(Recorded.json("diff-old.json")), current);

		assertThat(summary.text()).isEqualTo(Recorded.text("diff-same.md"));
		assertThat(summary.changed()).isFalse();
	}

	@Test
	void summarize_aWholeNumberPriceWrittenAsAFloat_isNoChange() {
		ObjectNode old = (ObjectNode) Recorded.json("diff-old.json");
		old.putObject("rates").put("storageGibMonth", 1);
		ObjectNode current = old.deepCopy();
		current.put("snapshotDate", "2026-11-01");
		current.putObject("rates").put("storageGibMonth", 1.0);

		PriceDiff.Summary summary = PriceDiff.summarize(Optional.of(old), current);

		assertThat(summary.changed()).isFalse();
	}

}
