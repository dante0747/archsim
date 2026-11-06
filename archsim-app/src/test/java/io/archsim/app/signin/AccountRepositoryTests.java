package io.archsim.app.signin;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.support.SameInstant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * People and their provider accounts, as the repository reads them for signing in.
 */
class AccountRepositoryTests extends HostedTestSupport {

	@Autowired
	private AccountRepository accounts;

	@Test
	void accountWithVerifiedEmail_accountsStoredAtTheSameInstant_isThePersonWithTheLowestIdentityId() {
		String email = newId() + "@example.org";
		List<String> lowestFirst = SameInstant.storeThree((identityId) -> personSignedUpAtNine(identityId, email));

		Optional<Account> account = this.accounts.accountWithVerifiedEmail(email);

		assertThat(account).map(Account::id).contains(personWith(lowestFirst.getFirst()));
	}

	/**
	 * Stores someone new whose GitHub account, with the email verified, was stored at
	 * nine o'clock.
	 * @param identityId the GitHub account's id here
	 * @param email the email, lower case
	 */
	private void personSignedUpAtNine(String identityId, String email) {
		String userId = newId();
		person(userId, "Ada");
		this.jdbc()
			.sql("""
					INSERT INTO identities (id, user_id, provider, subject, email, email_verified, created_at)
					VALUES (:id, :user, 'github', :id, :email, true, :at)""")
			.param("id", identityId)
			.param("user", userId)
			.param("email", email)
			.param("at", Timestamp.from(SameInstant.NINE))
			.update();
	}

	private String personWith(String identityId) {
		return this.jdbc()
			.sql("SELECT user_id FROM identities WHERE id = :id")
			.param("id", identityId)
			.query(String.class)
			.single();
	}

}
