package io.archsim.app.signin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sandbox's guest sends bodies of up to 256 KB, a small part of what an account may
 * send: designs are kilobytes, and a visitor who has not signed in cannot fill the
 * server's memory with a few large ones (ADR-0108).
 */
class GuestBodiesTests extends SandboxTestSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String VALIDATE = "/api/v1/validate";

	/** A little over a guest's cap. */
	private static final int OVER_THE_CAP = 300_000;

	@Test
	void validate_aGuestsDesignOverTheCap_isTooLarge() throws IOException {
		SignedIn guest = guest();

		MvcTestResult refused = send(guest, HttpMethod.POST, VALIDATE, design(OVER_THE_CAP));

		assertThat(refused).hasStatus(413);
		assertThat(json(refused).path("detail").path("message").asString())
			.isEqualTo("A sandbox's guest sends at most 256 KB at a time: sign in to send more.");
	}

	@Test
	void validate_aGuestsDesignUnderTheCap_isServed() throws IOException {
		SignedIn guest = guest();

		assertThat(send(guest, HttpMethod.POST, VALIDATE, design(0))).hasStatus(200);
	}

	@Test
	void validate_aSignedInPersonsDesignOverTheGuestsCap_isServed() throws IOException {
		SignedIn person = signedIn("Ada");

		assertThat(send(person, HttpMethod.POST, VALIDATE, design(OVER_THE_CAP))).hasStatus(200);
	}

	/**
	 * The starter design, with a description long enough that its body is at least the
	 * size asked.
	 */
	private static String design(int atLeast) throws IOException {
		Path root = Path.of(System.getProperty("archsim.repoRoot", ".."));
		ObjectNode document = (ObjectNode) JSON
			.readTree(Files.readString(root.resolve("examples/cache-aside-starter.json")));
		document.withObjectProperty("metadata").put("description", "d".repeat(atLeast));
		return JSON.writeValueAsString(JSON.createObjectNode().set("document", document));
	}

}
