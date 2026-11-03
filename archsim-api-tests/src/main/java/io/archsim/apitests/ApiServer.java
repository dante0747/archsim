package io.archsim.apitests;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A running server of one {@link Profile}: clients for it, its database, and (hosted)
 * signing in through the stand-in GitHub.
 *
 * <p>
 * A server outlives each test and may outlive a test run, so tests never assume an empty
 * server: each person they sign in as is new (a unique login), and each client sends its
 * own {@code X-ArchSim-Client-IP} (the servers trust the header from 127.0.0.1), so the
 * limits per address of one test never touch another's.
 */
public final class ApiServer {

	/** Picks the host part of an address in 198.18.0.0/16: its last two bytes. */
	private static final int HOST_MASK = 0xFFFF;

	/** Picks the last byte of an address. */
	private static final int BYTE_MASK = 0xFF;

	/** The bits in one byte of an address. */
	private static final int BITS_PER_BYTE = 8;

	private static final AtomicInteger ADDRESSES = new AtomicInteger(
			ThreadLocalRandom.current().nextInt(HOST_MASK + 1));

	private static final AtomicInteger PEOPLE = new AtomicInteger();

	/** Sets this test run's logins apart from earlier runs' on the same database. */
	private static final String RUN = Long.toString(System.currentTimeMillis() % 2_176_782_336L, 36);

	private final Profile profile;

	private final URI url;

	private final @Nullable Db db;

	private final @Nullable FakeGitHub github;

	/**
	 * Describes a running server.
	 * @param profile the server's profile
	 * @param url the server's base URL
	 * @param db the server's database, or null when the suite was not told where it is
	 * @param github the stand-in GitHub a hosted server signs people in with, or null for
	 * a server without sign-in
	 */
	public ApiServer(Profile profile, URI url, @Nullable Db db, @Nullable FakeGitHub github) {
		this.profile = profile;
		this.url = url;
		this.db = db;
		this.github = github;
	}

	public Profile profile() {
		return this.profile;
	}

	public URI url() {
		return this.url;
	}

	/**
	 * Returns a new client: no cookies, an address of its own.
	 * @return the client
	 */
	public ApiClient client() {
		return new ApiClient(this.url, (this.profile == Profile.HOSTED) ? Profile.HOSTED_PUBLIC_URL : null)
			.header("X-ArchSim-Client-IP", nextAddress());
	}

	/**
	 * Returns an address no other client of this test run has.
	 * @return an address from 198.18.0.0/16, a range for tests
	 */
	public static String nextAddress() {
		int host = ADDRESSES.getAndIncrement() & HOST_MASK;
		return "198.18." + (host >> BITS_PER_BYTE) + "." + (host & BYTE_MASK);
	}

	/**
	 * Returns the server's database; fails when the suite was not told where it is.
	 * @return the database
	 */
	public Db db() {
		if (this.db == null) {
			throw new IllegalStateException("this test reads or writes the " + this.profile.id()
					+ " server's database: set archsim.api.jdbc." + this.profile.id());
		}
		return this.db;
	}

	/**
	 * Returns the stand-in GitHub the hosted server signs people in with.
	 * @return the stand-in GitHub
	 */
	public FakeGitHub github() {
		if (this.github == null) {
			throw new IllegalStateException("only the hosted profile signs in (this is " + this.profile.id() + ")");
		}
		return this.github;
	}

	/**
	 * Adds a new GitHub account: login {@code <name>-<unique>}, display name
	 * {@code Name}, and a verified email {@code <login>@example.com}.
	 * @param name the person's name, which starts the login
	 * @return the account
	 */
	public FakeGitHub.Account newAccount(String name) {
		String login = (name + "-" + RUN + PEOPLE.incrementAndGet()).toLowerCase(Locale.ROOT);
		String display = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
		return github().add(login, login + "@example.com", true, display);
	}

	/**
	 * Makes the whole browser round trip as {@code login}: the API's login, GitHub's
	 * page, the API's callback. Like the web app, the client then sends the CSRF token
	 * with every change.
	 * @param client the client that signs in
	 * @param login the GitHub account to sign in as
	 * @return where the callback sent the browser
	 */
	public String signIn(ApiClient client, String login) {
		ApiResponse start = client.get("/auth/github/login").expectStatus(HttpURLConnection.HTTP_MOVED_TEMP);
		ApiResponse done = client.open(github().authorize(start.location(), login))
			.expectStatus(HttpURLConnection.HTTP_MOVED_TEMP);
		client.cookie("__Host-archsim_csrf").ifPresent((csrf) -> client.header("X-ArchSim-CSRF", csrf));
		return done.location();
	}

	/**
	 * Returns a client signed in as a new person (see {@link #newAccount}).
	 * @param name the start of the person's login
	 * @return the signed-in client
	 */
	public ApiClient signedIn(String name) {
		ApiClient client = client();
		String landed = signIn(client, newAccount(name).login());
		if (landed.startsWith("/signin")) {
			throw new AssertionError("signing in as " + name + " failed: " + landed);
		}
		return client;
	}

	/**
	 * Returns a new client that sends {@code person}'s new API token and no cookies, as a
	 * CLI would; fails the test unless the token is made.
	 * @param person the signed-in client that makes the token
	 * @param scope the token's scope: {@code read} or {@code write}
	 * @return the client, with the token in its {@code Authorization} header
	 */
	public ApiClient tokenClient(ApiClient person, String scope) {
		JsonNode created = person.post("/tokens", Json.object("name", "test " + scope, "scope", scope))
			.expectStatus(HttpURLConnection.HTTP_CREATED)
			.json();
		return client().header("Authorization", "Bearer " + created.path("token").asString());
	}

}
