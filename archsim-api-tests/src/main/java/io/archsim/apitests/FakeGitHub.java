package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

/**
 * A stand-in GitHub for sign-in: the authorize step (played by the test, as the browser's
 * visit), and, over HTTP for the API server to call, the token endpoint (which checks the
 * PKCE verifier against the challenge, like the real one) and the profile endpoints. Port
 * of the Python tests' {@code fake_oauth.py}.
 */
public final class FakeGitHub implements AutoCloseable {

	/** The OAuth client id the API server must present; the hosted profile sets it. */
	public static final String CLIENT_ID = "test-client";

	/** The OAuth client secret the API server must present with {@link #CLIENT_ID}. */
	public static final String CLIENT_SECRET = "test-secret";

	/** The smallest id a new account gets. */
	private static final long FIRST_ACCOUNT_ID = 1_000_000_000L;

	/** The bound (exclusive) of the ids new accounts get. */
	private static final long ACCOUNT_ID_BOUND = 1L << 52;

	/** How many random bytes make a code or a token. */
	private static final int TOKEN_BYTES = 16;

	/** The token endpoint's error for a code it does not accept. */
	private static final String BAD_VERIFICATION_CODE = "bad_verification_code";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final HttpServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final Map<String, Account> accounts = new ConcurrentHashMap<>();

	private final Map<String, Grant> codes = new ConcurrentHashMap<>();

	private final Map<String, String> tokens = new ConcurrentHashMap<>();

	private final Set<String> refusing = ConcurrentHashMap.newKeySet();

	private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

	private FakeGitHub(HttpServer server) {
		this.server = server;
	}

	/**
	 * Starts a stand-in GitHub on the loopback address.
	 * @param port the port to listen on, or 0 for any free port
	 * @return the running stand-in, to close when done
	 */
	public static FakeGitHub start(int port) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
			FakeGitHub fake = new FakeGitHub(server);
			server.createContext("/", fake::handle);
			server.setExecutor(fake.executor);
			server.start();
			return fake;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("the stand-in GitHub cannot listen on port " + port, ex);
		}
	}

	/**
	 * Returns where the stand-in listens.
	 * @return its address: a hosted server's GitHub URL and GitHub API URL
	 */
	public URI url() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
	}

	/**
	 * Adds an account; its id is random, so it is new to a database that outlives one
	 * test run.
	 * @param login the account's unique name, which people sign in with
	 * @param email the account's primary email, or null when it has none
	 * @param verified whether GitHub has verified the email
	 * @param name the name shown for the account
	 * @return the new account
	 */
	public Account add(String login, @Nullable String email, boolean verified, String name) {
		long id = ThreadLocalRandom.current().nextLong(FIRST_ACCOUNT_ID, ACCOUNT_ID_BOUND);
		Account account = new Account(id, login, name, email, verified);
		this.accounts.put(login, account);
		return account;
	}

	/**
	 * From now on, makes the token endpoint refuse the codes given to an account.
	 * @param login the account whose codes to refuse
	 */
	public void refuseTokensFor(String login) {
		this.refusing.add(login);
	}

	/**
	 * Returns the token requests the API made.
	 * @return each request's form: code, verifier, redirect URI, client
	 */
	public List<Map<String, String>> tokenRequests() {
		return List.copyOf(this.tokenRequests);
	}

	/**
	 * Plays the browser's visit to GitHub's sign-in page, which approves at once.
	 * @param authorizeUrl where the API sent the browser
	 * @param login the account that signs in
	 * @return the URL GitHub sends the browser back to: the API's callback with a code
	 */
	public String authorize(String authorizeUrl, String login) {
		Map<String, String> query = form(URI.create(authorizeUrl).getRawQuery());
		check(CLIENT_ID.equals(query.get("client_id")), "wrong client id: " + query.get("client_id"));
		check("code".equals(query.get("response_type")), "response_type must be code");
		check("S256".equals(query.get("code_challenge_method")), "PKCE (S256) is required");
		String challenge = required(query, "code_challenge");
		String redirectUri = required(query, "redirect_uri");
		String state = required(query, "state");
		String code = randomToken();
		this.codes.put(code, new Grant(challenge, redirectUri, login));
		return redirectUri + "?code=" + encode(code) + "&state=" + encode(state);
	}

	@Override
	public void close() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			Answer answer = answer(exchange);
			byte[] bytes = Json.write(answer.body()).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(answer.status(), bytes.length);
			exchange.getResponseBody().write(bytes);
		}
	}

	private Answer answer(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		if ("POST".equals(method) && path.endsWith("/login/oauth/access_token")) {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			return token(form(body));
		}
		if ("GET".equals(method)) {
			return profile(path, exchange.getRequestHeaders().getFirst("Authorization"));
		}
		return new Answer(HttpURLConnection.HTTP_BAD_METHOD, Map.of("message", "Method Not Allowed"));
	}

	private Answer token(Map<String, String> form) {
		this.tokenRequests.add(Map.copyOf(form));
		Grant peek = this.codes.get(form.getOrDefault("code", ""));
		if (peek != null && this.refusing.contains(peek.login())) {
			return new Answer(HttpURLConnection.HTTP_BAD_REQUEST, Map.of("error", BAD_VERIFICATION_CODE));
		}
		if (!CLIENT_ID.equals(form.get("client_id")) || !CLIENT_SECRET.equals(form.get("client_secret"))) {
			return new Answer(HttpURLConnection.HTTP_UNAUTHORIZED, Map.of("error", "incorrect_client_credentials"));
		}
		Grant grant = this.codes.remove(form.getOrDefault("code", ""));
		if (grant == null || !grant.redirectUri().equals(form.get("redirect_uri"))) {
			return new Answer(HttpURLConnection.HTTP_BAD_REQUEST, Map.of("error", BAD_VERIFICATION_CODE));
		}
		if (!s256(form.getOrDefault("code_verifier", "")).equals(grant.challenge())) {
			return new Answer(HttpURLConnection.HTTP_BAD_REQUEST,
					Map.of("error", "invalid_grant", "error_description", "PKCE verification failed"));
		}
		String token = randomToken();
		this.tokens.put(token, grant.login());
		return new Answer(HttpURLConnection.HTTP_OK,
				Map.of("access_token", token, "token_type", "bearer", "scope", "read:user"));
	}

	private Answer profile(String path, @Nullable String authorization) {
		Account account = accountOf(authorization);
		if (account == null) {
			return new Answer(HttpURLConnection.HTTP_UNAUTHORIZED, Map.of("message", "Bad credentials"));
		}
		if (path.endsWith("/user/emails")) {
			return new Answer(HttpURLConnection.HTTP_OK, emails(account));
		}
		if (path.endsWith("/user")) {
			return new Answer(HttpURLConnection.HTTP_OK, user(account));
		}
		return new Answer(HttpURLConnection.HTTP_NOT_FOUND, Map.of("message", "Not Found"));
	}

	private @Nullable Account accountOf(@Nullable String authorization) {
		String token = (authorization != null) ? authorization.replaceFirst("^(?i)bearer ", "") : "";
		String login = this.tokens.get(token);
		return (login != null) ? this.accounts.get(login) : null;
	}

	private static List<Map<String, Object>> emails(Account account) {
		String email = account.email();
		if (email == null) {
			return List.of();
		}
		return List.of(Map.of("email", email, "primary", true, "verified", account.verified()));
	}

	private static Map<String, @Nullable Object> user(Account account) {
		Map<String, @Nullable Object> user = new HashMap<>();
		user.put("id", account.id());
		user.put("login", account.login());
		user.put("name", account.name());
		// GitHub hides the email unless it is public, so the API asks /user/emails
		user.put("email", null);
		user.put("avatar_url", "https://avatars.example.com/" + account.id());
		return user;
	}

	private static String s256(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String randomToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * Reads a query string or a form body.
	 * @param text the query string or form body, or null for none
	 * @return the first value of each name, decoded
	 */
	static Map<String, String> form(@Nullable String text) {
		Map<String, String> values = new HashMap<>();
		if (text == null || text.isEmpty()) {
			return values;
		}
		for (String pair : text.split("&", -1)) {
			String[] nameAndValue = pair.split("=", 2);
			values.putIfAbsent(decode(nameAndValue[0]), (nameAndValue.length > 1) ? decode(nameAndValue[1]) : "");
		}
		return values;
	}

	private static String required(Map<String, String> query, String name) {
		String value = query.get(name);
		check(value != null && !value.isEmpty(), "the authorize URL has no " + name);
		return (value != null) ? value : "";
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	private static String decode(String text) {
		return URLDecoder.decode(text, StandardCharsets.UTF_8);
	}

	/**
	 * An account people sign in as.
	 *
	 * @param id GitHub's number for the account
	 * @param login the account's unique name, which people sign in with
	 * @param name the name shown for the account
	 * @param email the account's primary email, or null when it has none
	 * @param verified whether GitHub has verified the email
	 */
	public record Account(long id, String login, String name, @Nullable String email, boolean verified) {
	}

	/** A code GitHub gave the browser, waiting for the API to trade it for a token. */
	private record Grant(String challenge, String redirectUri, String login) {
	}

	/** What the stand-in answers the API: a status and a body to write as JSON. */
	private record Answer(int status, Object body) {
	}

}
