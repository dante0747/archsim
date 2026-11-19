package io.archsim.benchmarks.report;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page's list of the held-out experiments that are registered and have no kept run
 * (the second set, ADR-0090, until it is measured): what each runs, when it was
 * registered, and how many there are.
 */
class RegisteredBlockTests {

	@Test
	void of_experimentsAwaitingMeasurement_listsWhatEachRunsAndWhenItWasRegistered() {
		String block = RegisteredBlock.of(List.of(experiment("java-hot-row"), experiment("go-pool")));

		assertThat(block.lines()).containsExactly("| Experiment | What it runs | Registered |", "| --- | --- | --- |",
				"| java-hot-row | Spring Boot (Tomcat, HikariCP) + PostgreSQL: every request updates the same row | "
						+ "2026-11-19 |",
				"| go-pool | Go (net/http, pgx) + PostgreSQL through a pool of 2 connections: 50% reads, 50% writes "
						+ "over 100,000 rows | 2026-11-19 |",
				"",
				"2 held-out experiments are registered with no kept run: nothing on this page is measured on them.");
	}

	@Test
	void of_oneExperimentAwaitingMeasurement_countsItAlone() {
		String block = RegisteredBlock.of(List.of(experiment("cache-miss")));

		assertThat(block.lines()).last()
			.isEqualTo("1 held-out experiment is registered with no kept run: nothing on this page is measured on it.");
	}

	@Test
	void of_noExperimentAwaitingMeasurement_saysEveryOneIsMeasured() {
		assertThat(RegisteredBlock.of(List.of()))
			.isEqualTo("_Every registered held-out experiment has been measured._");
	}

	private static Experiment experiment(String id) {
		return Experiments.standard().get(id);
	}

}
