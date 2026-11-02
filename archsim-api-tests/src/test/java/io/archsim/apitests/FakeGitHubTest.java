package io.archsim.apitests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FakeGitHubTest {
    private static final String CALLBACK = "https://archsim.test/api/v1/auth/github/callback";
    private static final String VERIFIER = "a-verifier-of-enough-length-0123456789-abcdefghij";

    private final FakeGitHub github = FakeGitHub.start(0);
    private final HttpClient http = HttpClient.newHttpClient();

    FakeGitHubTest() {
        github.add("ada", "ada@example.com", true, "Ada");
    }

    @AfterEach
    void stop() {
        github.close();
        http.close();
    }

    @Test
    void aVerifierMatchingTheChallengeGetsATokenForTheProfile() throws Exception {
        String code = code("ada", challenge(VERIFIER));
        HttpResponse<String> token = exchange(code, VERIFIER, CALLBACK);
        assertThat(token.statusCode()).isEqualTo(200);
        String bearer =
                "Bearer " + Json.parse(token.body()).path("access_token").asString();
        JsonNode user = Json.parse(profile("/user", bearer).body());
        assertThat(user.path("login").asString()).isEqualTo("ada");
        assertThat(user.path("email").isNull()).isTrue(); // hidden, as GitHub does
        JsonNode emails = Json.parse(profile("/user/emails", bearer).body());
        assertThat(emails.get(0).path("email").asString()).isEqualTo("ada@example.com");
        assertThat(github.tokenRequests())
                .singleElement()
                .satisfies(form -> assertThat(form).containsEntry("code_verifier", VERIFIER));
        assertThat(exchange(code, VERIFIER, CALLBACK).statusCode()).isEqualTo(400); // good once
    }

    @Test
    void aWrongVerifierRedirectOrTokenIsRefused() throws Exception {
        HttpResponse<String> wrong = exchange(code("ada", challenge(VERIFIER)), "another-verifier", CALLBACK);
        assertThat(Json.parse(wrong.body()).path("error").asString()).isEqualTo("invalid_grant");
        assertThat(exchange(code("ada", challenge(VERIFIER)), VERIFIER, "https://evil.example/cb")
                        .statusCode())
                .isEqualTo(400);
        assertThat(profile("/user", "Bearer nope").statusCode()).isEqualTo(401);
    }

    @Test
    void refusedLoginsGetNoToken() throws Exception {
        github.refuseTokensFor("ada");
        assertThat(exchange(code("ada", challenge(VERIFIER)), VERIFIER, CALLBACK)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void authorizeInsistsOnPkce() {
        String url = "https://github.test/login/oauth/authorize?client_id=" + FakeGitHub.CLIENT_ID
                + "&response_type=code&state=s&redirect_uri=" + CALLBACK;
        assertThatThrownBy(() -> github.authorize(url, "ada")).hasMessageContaining("PKCE");
    }

    private String code(String login, String challenge) {
        String url = "https://github.test/login/oauth/authorize?client_id=" + FakeGitHub.CLIENT_ID
                + "&response_type=code&code_challenge_method=S256&code_challenge=" + challenge
                + "&state=the-state&redirect_uri=" + encode(CALLBACK);
        URI back = URI.create(github.authorize(url, login));
        assertThat(back.toString()).startsWith(CALLBACK + "?code=").endsWith("&state=the-state");
        return FakeGitHub.form(back.getRawQuery()).getOrDefault("code", "");
    }

    private HttpResponse<String> exchange(String code, String verifier, String redirectUri) throws Exception {
        String form = "client_id=" + FakeGitHub.CLIENT_ID + "&client_secret=" + FakeGitHub.CLIENT_SECRET + "&code="
                + encode(code) + "&code_verifier=" + encode(verifier) + "&redirect_uri=" + encode(redirectUri);
        HttpRequest request = HttpRequest.newBuilder(github.url().resolve("/login/oauth/access_token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(form))
                .build();
        return http.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> profile(String path, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(github.url().resolve(path))
                .header("Authorization", authorization)
                .build();
        return http.send(request, BodyHandlers.ofString());
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
