package io.archsim.benchmarks.parity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/validation/java-parity.md} regenerated from its committed inputs is the
 * committed page, as CI's check of it was: every case meeting the bar, and every run that
 * does not replay the frozen engine exactly departing only where the baselines keep too
 * little to read it as the frozen engine's.
 */
class CommittedParityPageTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final JavaParity PARITY = new JavaParity(LAYOUT.root());

	@Test
	void missing_theCommittedInputs_isEmpty() {
		assertThat(PARITY.missing()).isEmpty();
	}

	@Test
	void page_theCommittedInputs_isTheCommittedPage() throws IOException {
		String page = PARITY.page();

		assertThat(page)
			.isEqualTo(Files.readString(LAYOUT.validation().resolve("java-parity.md"), StandardCharsets.UTF_8));
	}

	@Test
	void page_theCommittedInputs_meetTheBarAndDepartOnlyWhereTheBaselinesKeepTooLittle() {
		String page = PARITY.page();

		assertThat(page).contains("**Stage 4's bar is met.**").contains("; none departs otherwise.");
	}

}
