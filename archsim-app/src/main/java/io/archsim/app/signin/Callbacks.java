package io.archsim.app.signin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import io.archsim.app.access.Visitor;

/**
 * Finishes signing in when the provider sends the browser back: the state must match the
 * cookie's, the code is exchanged with the verifier, the person behind the account is
 * found or created, and a session starts. A failure lands on the sign-in page, saying why
 * ({@code /signin?error=<reason>}).
 */
@Service
class Callbacks {

	private final OAuthClient client;

	private final StateCookies states;

	private final Accounts accounts;

	private final Sessions sessions;

	Callbacks(OAuthClient client, StateCookies states, Accounts accounts, Sessions sessions) {
		this.client = client;
		this.states = states;
		this.accounts = accounts;
		this.sessions = sessions;
	}

	/**
	 * Finishes signing in.
	 * @param provider the provider in the callback's path
	 * @param callback what the provider sent back
	 * @param stateCookie the state cookie, if the browser kept it
	 * @param visitor where the request comes from, which the session records
	 * @return the redirect to where sign-in started, with the session's cookies, or to
	 * the sign-in page with the reason it failed
	 */
	Redirect finish(OAuthProvider provider, Callback callback, @Nullable String stateCookie, Visitor visitor) {
		List<String> cookies = new ArrayList<>(List.of(this.states.clearCookie()));
		try {
			Verified verified = verified(provider, callback, stateCookie);
			ProviderProfile profile = this.client.profile(provider, verified.code(), verified.state().verifier());
			String userId = this.accounts.signIn(provider.name(), profile);
			cookies.addAll(this.sessions.start(userId, visitor));
			// the cookie is signed, but where to land is checked again on the way out
			return new Redirect(Landing.safePath(verified.state().next()), cookies);
		}
		catch (SignInFailure ex) {
			return new Redirect("/signin?error=" + URLEncoder.encode(ex.code(), StandardCharsets.UTF_8), cookies);
		}
	}

	private Verified verified(OAuthProvider provider, Callback callback, @Nullable String stateCookie) {
		if (isGiven(callback.error())) {
			throw SignInFailure.denied();
		}
		String code = callback.code();
		String state = callback.state();
		if (code == null || state == null || code.isEmpty() || state.isEmpty()) {
			throw SignInFailure.state();
		}
		SignInState saved = this.states.verify(stateCookie)
			.filter((kept) -> kept.provider().equals(provider.name()) && same(kept.state(), state))
			.orElseThrow(SignInFailure::state);
		return new Verified(saved, code);
	}

	private static boolean isGiven(@Nullable String value) {
		return value != null && !value.isEmpty();
	}

	private static boolean same(String kept, String sent) {
		return MessageDigest.isEqual(kept.getBytes(StandardCharsets.UTF_8), sent.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * What the provider sent back, from the callback's query.
	 *
	 * @param code the code to exchange, if the person agreed
	 * @param state the sign-in's state
	 * @param error why the provider refused, if it did
	 */
	record Callback(@Nullable String code, @Nullable String state, @Nullable String error) {

	}

	/**
	 * A callback whose state matches its cookie's.
	 *
	 * @param state the sign-in the cookie kept
	 * @param code the code to exchange
	 */
	private record Verified(SignInState state, String code) {

	}

}
