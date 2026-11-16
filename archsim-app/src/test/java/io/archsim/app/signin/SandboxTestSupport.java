package io.archsim.app.signin;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.BrowserCsrf;
import io.archsim.app.access.Principal;
import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the tests of sandboxes share: starting one as the web app does, from an address of
 * the test's own, and the guest it signs in.
 */
abstract class SandboxTestSupport extends HostedTestSupport {

	static final String CONFIG = "/api/v1/auth/config";

	static final String SANDBOX = "/api/v1/auth/sandbox";

	/**
	 * The header the web server tells the visitor's address in; the tests' is trusted.
	 */
	private static final String CLIENT_IP = "X-ArchSim-Client-IP";

	/** The addresses of 198.18.0.0/16, a range for tests, one per visitor. */
	private static final AtomicInteger ADDRESSES = new AtomicInteger();

	private static final int BYTE = 256;

	/**
	 * An address no other visitor of these tests has, so each test's limit per address is
	 * its own.
	 * @return the address
	 */
	static String newAddress() {
		int host = ADDRESSES.incrementAndGet();
		return "198.18." + (host / BYTE) + "." + (host % BYTE);
	}

	/**
	 * Starts a sandbox as the web app does: the CSRF token from {@code /auth/config},
	 * then {@code POST /auth/sandbox} with it.
	 * @param address where the visitor is
	 * @return the answer
	 */
	MvcTestResult start(String address) {
		String csrf = setCookie(this.mvc().get().uri(CONFIG).exchange(), BrowserCsrf.COOKIE)
			.orElseThrow(() -> new AssertionError("/auth/config set no CSRF cookie"));
		return this.mvc()
			.post()
			.uri(SANDBOX)
			.header(CLIENT_IP, address)
			.cookie(new Cookie(BrowserCsrf.COOKIE, csrf))
			.header(BrowserCsrf.HEADER, csrf)
			.exchange();
	}

	/**
	 * A visitor who tried ArchSim without signing in, as starting a sandbox leaves them.
	 * @return the guest, their sandbox, and their browser's session and CSRF token
	 */
	SignedIn guest() {
		MvcTestResult started = start(newAddress());
		assertThat(started).hasStatus(201);
		JsonNode me = json(started);
		return new SignedIn(me.path("user").path("id").asString(), me.path("workspaces").path(0).path("id").asString(),
				setCookie(started, Principal.SESSION_COOKIE).orElseThrow(),
				setCookie(started, BrowserCsrf.COOKIE).orElseThrow());
	}

}
