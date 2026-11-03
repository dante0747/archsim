package io.archsim.app.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the history keeps of a comment or a note: its start, on one line.
 */
class AuditLogTests {

	@Test
	void excerpt_shortText_isItOnOneLine() {
		assertThat(AuditLog.excerpt("  Looks good,\n\nship it  ")).isEqualTo("Looks good, ship it");
	}

	@Test
	void excerpt_textOfTheMostCharacters_isWhole() {
		String text = "x".repeat(140);

		assertThat(AuditLog.excerpt(text)).isEqualTo(text);
	}

	@Test
	void excerpt_longerText_isCutWithAnEllipsis() {
		assertThat(AuditLog.excerpt("y".repeat(141))).isEqualTo("y".repeat(139) + "…");
	}

	@Test
	void excerpt_cutAfterASpace_dropsTheSpace() {
		assertThat(AuditLog.excerpt("z".repeat(138) + " and more")).isEqualTo("z".repeat(138) + "…");
	}

}
