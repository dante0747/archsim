package io.archsim.app.access;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Who is asking: a sandbox's guest is a browser session, which may use its sandbox but
 * not what takes an account of one's own.
 */
class PrincipalTests {

	@Test
	void guest_aBrowserSession_isAGuestsSession() {
		Principal guest = Principal.guest("g1", "s1", "198.51.100.7");

		assertThat(guest.kind()).isEqualTo(PrincipalKind.SESSION);
		assertThat(guest.guest()).isTrue();
	}

	@Test
	void session_ofSomeoneSignedIn_isNoGuest() {
		assertThat(Principal.session("u1", "s1", null).guest()).isFalse();
	}

	@Test
	void constructor_guestWithAToken_isRefused() {
		TokenGrant grant = new TokenGrant(null, false);

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new Principal("g1", PrincipalKind.TOKEN, null, null, grant, true));
	}

	@Test
	void requireAccount_guest_isForbiddenSayingToSignIn() {
		Principal guest = Principal.guest("g1", "s1", null);

		assertThatExceptionOfType(ApiException.class).isThrownBy(guest::requireAccount).satisfies((ex) -> {
			assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(ex.getMessage()).isEqualTo(Principal.GUESTS_CANNOT);
		});
	}

	@Test
	void requireAccount_someoneSignedIn_passes() {
		assertThatNoException().isThrownBy(Principal.session("u1", "s1", null)::requireAccount);
	}

	@Test
	void requirePerson_guest_isForbiddenSayingToSignIn() {
		Principal guest = Principal.guest("g1", "s1", null);

		assertThatExceptionOfType(ApiException.class).isThrownBy(guest::requirePerson)
			.withMessage(Principal.GUESTS_CANNOT);
	}

	@Test
	void requirePerson_localUser_passes() {
		assertThatNoException().isThrownBy(Principal.local(null)::requirePerson);
	}

}
