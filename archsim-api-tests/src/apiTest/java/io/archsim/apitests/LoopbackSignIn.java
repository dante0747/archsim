package io.archsim.apitests;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Assumptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing in as a browser that connects from a chosen loopback address, for the OAuth
 * tests that need the server to meet a peer other than 127.0.0.1. {@link ApiClient}
 * always connects from 127.0.0.1, so this goes through the JDK's HTTP client instead.
 */
final class LoopbackSignIn {

	private LoopbackSignIn() {
	}

	/**
	 * The browser round trip as {@code account}, from the local address {@code from},
	 * sending {@code X-ArchSim-Client-IP: claimed}. Returns the session cookie. Skips the
	 * test where the machine cannot connect from that address.
	 * @param from the local address to connect from
	 * @param claimed the client address the request claims in its header
	 * @param server the server to sign in to
	 * @param account the stand-in GitHub account that signs in
	 * @return the value of the session cookie the server set
	 * @throws IOException if a request fails
	 * @throws InterruptedException if interrupted while waiting for an answer
	 */
	static String signIn(String from, String claimed, ApiServer server, FakeGitHub.Account account)
			throws IOException, InterruptedException {
		InetAddress local = InetAddress.getByName(from);
		requireBindable(local, from);
		try (HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.localAddress(local)
			.build()) {
			HttpResponse<String> start = http
				.send(HttpRequest.newBuilder(server.url().resolve(ApiClient.API + "/auth/github/login"))
					.header("X-ArchSim-Client-IP", claimed)
					.build(), BodyHandlers.ofString());
			assertThat(start.statusCode()).as(start.body()).isEqualTo(302);
			String back = server.github()
				.authorize(start.headers().firstValue("location").orElseThrow(), account.login());
			assertThat(back).startsWith(Profile.HOSTED_PUBLIC_URL + "/");
			HttpResponse<String> done = http
				.send(HttpRequest.newBuilder(server.url().resolve(back.substring(Profile.HOSTED_PUBLIC_URL.length())))
					.header("X-ArchSim-Client-IP", claimed)
					.header("Cookie", OAuthTests.STATE + "=" + cookieValue(start, OAuthTests.STATE))
					.build(), BodyHandlers.ofString());
			assertThat(done.statusCode()).as(done.body()).isEqualTo(302);
			assertThat(done.headers().firstValue("location")).hasValue("/");
			return cookieValue(done, OAuthTests.SESSION);
		}
	}

	/** Skips the test when this machine cannot connect from {@code local}. */
	private static void requireBindable(InetAddress local, String from) throws IOException {
		try (Socket probe = new Socket()) {
			probe.bind(new InetSocketAddress(local, 0));
		}
		catch (BindException ex) {
			Assumptions.abort("this machine cannot connect from " + from + ": " + ex.getMessage());
		}
	}

	/**
	 * The value of the cookie {@code name} that the response sets; fails the test without
	 * one.
	 */
	private static String cookieValue(HttpResponse<?> response, String name) {
		return response.headers()
			.allValues("set-cookie")
			.stream()
			.filter((cookie) -> cookie.startsWith(name + "="))
			.map((cookie) -> cookie.split(";", 2)[0].substring(name.length() + 1))
			.findFirst()
			.orElseThrow(() -> new AssertionError(response.uri() + " sets no " + name));
	}

}
