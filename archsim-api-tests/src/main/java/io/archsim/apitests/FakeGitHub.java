package io.archsim.apitests;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.jspecify.annotations.Nullable;

/**
 * A stand-in GitHub for sign-in: the authorize step (played by the test, as the browser's visit),
 * and, over HTTP for the API server to call, the token endpoint (which checks the PKCE verifier
 * against the challenge, like the real one) and the profile endpoints. Port of the Python
 * tests' {@code fake_oauth.py}.
 */
public final class FakeGitHub implements AutoCloseable {
    public static final String CLIENT_ID = "test-client";
    public static final String CLIENT_SECRET = "test-secret";

    /** An account people sign in as. */
    public record Account(
            long id, String login, String name, @Nullable String email, boolean verified) {}

    private record Grant(String challenge, String redirectUri, String login) {}

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

    /** Listens on 127.0.0.1:{@code port} (0: any free port). */
    public static FakeGitHub start(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            FakeGitHub fake = new FakeGitHub(server);
            server.createContext("/", fake::handle);
            server.setExecutor(fake.executor);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException("the stand-in GitHub cannot listen on port " + port, e);
        }
    }

    /** Where it listens; a hosted server's GitHub URL and GitHub API URL. */
    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** An account; its id is random, so it is new to a database that outlives one test run. */
    public Account add(String login, @Nullable String email, boolean verified, String name) {
        Account account = new Account(
                ThreadLocalRandom.current().nextLong(1_000_000_000L, 1L << 52), login, name, email, verified);
        accounts.put(login, account);
        return account;
    }

    /** From now on, the token endpoint refuses the codes given to {@code login}. */
    public void refuseTokensFor(String login) {
        refusing.add(login);
    }

    /** The token requests the API made, each a form: code, verifier, redirect URI, client. */
    public List<Map<String, String>> tokenRequests() {
        return List.copyOf(tokenRequests);
    }

    /**
     * The browser's visit to GitHub's sign-in page, which approves at once as {@code login}:
     * returns the URL GitHub sends the browser back to (the API's callback with a code).
     */
    public String authorize(String authorizeUrl, String login) {
        Map<String, String> q = form(URI.create(authorizeUrl).getRawQuery());
        check(CLIENT_ID.equals(q.get("client_id")), "wrong client id: " + q.get("client_id"));
        check("code".equals(q.get("response_type")), "response_type must be code");
        check("S256".equals(q.get("code_challenge_method")), "PKCE (S256) is required");
        String challenge = required(q, "code_challenge");
        String redirectUri = required(q, "redirect_uri");
        String state = required(q, "state");
        String code = randomToken();
        codes.put(code, new Grant(challenge, redirectUri, login));
        return redirectUri + "?code=" + encode(code) + "&state=" + encode(state);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // -- the API's calls -------------------------------------------------------------------------
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            Answer answer;
            if (exchange.getRequestMethod().equals("POST") && path.endsWith("/login/oauth/access_token")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                answer = token(form(body));
            } else if (exchange.getRequestMethod().equals("GET")) {
                answer = profile(path, exchange.getRequestHeaders().getFirst("Authorization"));
            } else {
                answer = new Answer(405, Map.of("message", "Method Not Allowed"));
            }
            byte[] bytes = Json.write(answer.body()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private record Answer(int status, Object body) {}

    private Answer token(Map<String, String> form) {
        tokenRequests.add(Map.copyOf(form));
        Grant peek = codes.get(form.getOrDefault("code", ""));
        if (peek != null && refusing.contains(peek.login())) {
            return new Answer(400, Map.of("error", "bad_verification_code"));
        }
        if (!CLIENT_ID.equals(form.get("client_id")) || !CLIENT_SECRET.equals(form.get("client_secret"))) {
            return new Answer(401, Map.of("error", "incorrect_client_credentials"));
        }
        Grant grant = codes.remove(form.getOrDefault("code", ""));
        if (grant == null || !grant.redirectUri().equals(form.get("redirect_uri"))) {
            return new Answer(400, Map.of("error", "bad_verification_code"));
        }
        if (!s256(form.getOrDefault("code_verifier", "")).equals(grant.challenge())) {
            return new Answer(400, Map.of("error", "invalid_grant", "error_description", "PKCE verification failed"));
        }
        String token = randomToken();
        tokens.put(token, grant.login());
        return new Answer(200, Map.of("access_token", token, "token_type", "bearer", "scope", "read:user"));
    }

    private Answer profile(String path, @Nullable String authorization) {
        String token = authorization == null ? "" : authorization.replaceFirst("^(?i)bearer ", "");
        String login = tokens.get(token);
        Account account = login == null ? null : accounts.get(login);
        if (account == null) {
            return new Answer(401, Map.of("message", "Bad credentials"));
        }
        if (path.endsWith("/user/emails")) {
            String email = account.email();
            return new Answer(
                    200,
                    email == null
                            ? List.of()
                            : List.of(Map.of("email", email, "primary", true, "verified", account.verified())));
        }
        if (path.endsWith("/user")) {
            Map<String, @Nullable Object> user = new HashMap<>();
            user.put("id", account.id());
            user.put("login", account.login());
            user.put("name", account.name());
            user.put("email", null); // GitHub hides it unless public: the API asks /user/emails
            user.put("avatar_url", "https://avatars.example.com/" + account.id());
            return new Answer(200, user);
        }
        return new Answer(404, Map.of("message", "Not Found"));
    }

    // -- helpers ----------------------------------------------------------------------------------
    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A query string or form body, first value of each name. */
    static Map<String, String> form(@Nullable String text) {
        Map<String, String> out = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String pair : text.split("&", -1)) {
            String[] kv = pair.split("=", 2);
            out.putIfAbsent(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return out;
    }

    private static String required(Map<String, String> q, String name) {
        String value = q.get(name);
        check(value != null && !value.isEmpty(), "the authorize URL has no " + name);
        return value == null ? "" : value;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
