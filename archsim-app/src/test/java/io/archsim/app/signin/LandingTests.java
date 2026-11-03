package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a browser lands after signing in: a path on this site, never another site.
 */
class LandingTests {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "//evil.example/x", "https://evil.example/", "/\\evil.example", "projects",
			"/http://evil.example", "/a\tb", "/a\r\nSet-Cookie: x" })
	void safePath_foreignOrOdd_isTheHomePage(String next) {
		assertThat(Landing.safePath(next)).isEqualTo("/");
	}

	@Test
	void safePath_pathOnThisSite_isKept() {
		assertThat(Landing.safePath("/projects/abc")).isEqualTo("/projects/abc");
	}

	@Test
	void safePath_addressInTheQuery_isKept() {
		assertThat(Landing.safePath("/a?b=http://x")).isEqualTo("/a?b=http://x");
	}

	@Test
	void safePath_long_isCutToFiveHundredCharacters() {
		assertThat(Landing.safePath("/" + "😀".repeat(600)).codePoints().count()).isEqualTo(500);
	}

	@Test
	void location_withSpacesAndNonAscii_isQuotedAsStarletteQuotesIt() {
		assertThat(Landing.location("/projects/a b?x=é&y=<1>|\"q\""))
			.isEqualTo("/projects/a%20b?x=%C3%A9&y=%3C1%3E%7C%22q%22");
	}

}
