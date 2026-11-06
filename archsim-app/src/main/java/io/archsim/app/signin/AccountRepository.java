package io.archsim.app.signin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * People and the provider accounts they sign in with: the {@code users} and
 * {@code identities} tables.
 */
@Repository
class AccountRepository {

	private final JdbcClient jdbc;

	AccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The provider account with a provider's id.
	 * @param provider {@code github} or {@code google}
	 * @param subject the provider's id of the account
	 * @return the account, if someone signed in with it before
	 */
	Optional<Identity> identity(String provider, String subject) {
		return this.jdbc.sql("SELECT id, user_id FROM identities WHERE provider = :provider AND subject = :subject")
			.param("provider", provider)
			.param("subject", subject)
			.query((rows, number) -> new Identity(rows.getString("id"), rows.getString("user_id")))
			.optional();
	}

	Optional<UserView> user(String id) {
		return this.jdbc.sql("SELECT id, name, email, avatar_url FROM users WHERE id = :id")
			.param("id", id)
			.query((rows, number) -> new UserView(rows.getString("id"), rows.getString("name"), rows.getString("email"),
					rows.getString("avatar_url")))
			.optional();
	}

	Optional<Account> account(String userId) {
		return this.jdbc.sql("SELECT id, disabled_at IS NOT NULL AS disabled FROM users WHERE id = :id")
			.param("id", userId)
			.query((rows, number) -> new Account(rows.getString("id"), rows.getBoolean("disabled")))
			.optional();
	}

	/**
	 * The person a verified email belongs to, through any provider account.
	 * @param email the email, lower case
	 * @return the person, if any account has it verified: of several, the one whose
	 * account was stored first, by id among accounts stored at the same instant (by two
	 * of the API's servers), so it is always the same person
	 */
	Optional<Account> accountWithVerifiedEmail(String email) {
		return this.jdbc.sql("""
				SELECT u.id, u.disabled_at IS NOT NULL AS disabled
				FROM identities i JOIN users u ON u.id = i.user_id
				WHERE i.email = :email AND i.email_verified ORDER BY i.created_at, i.id LIMIT 1""")
			.param("email", email)
			.query((rows, number) -> new Account(rows.getString("id"), rows.getBoolean("disabled")))
			.optional();
	}

	void addUser(String id, ProviderProfile profile, Instant now) {
		this.jdbc.sql("INSERT INTO users (id, name, email, created_at) VALUES (:id, :name, :email, :now)")
			.param("id", id)
			.param("name", profile.name())
			.param("email", profile.emailVerified() ? profile.email() : null)
			.param("now", Timestamp.from(now))
			.update();
	}

	/**
	 * Updates a person from what the provider says now: name, picture, a verified email,
	 * when they were last seen.
	 * @param userId the person
	 * @param profile what the provider says
	 * @param now the time
	 */
	void updateUser(String userId, ProviderProfile profile, Instant now) {
		this.jdbc.sql("""
				UPDATE users SET name = :name, avatar_url = COALESCE(:avatar, avatar_url),
				                 email = COALESCE(:email, email), last_seen_at = :now
				WHERE id = :id""")
			.param("id", userId)
			.param("name", profile.name())
			.param("avatar", profile.avatarUrl())
			.param("email", profile.emailVerified() ? profile.email() : null)
			.param("now", Timestamp.from(now))
			.update();
	}

	void addIdentity(Identity identity, String provider, ProviderProfile profile, Instant now) {
		this.jdbc.sql("""
				INSERT INTO identities (id, user_id, provider, subject, email, email_verified, login, created_at,
				                        last_login_at)
				VALUES (:id, :user, :provider, :subject, :email, :verified, :login, :now, :now)""")
			.param("id", identity.id())
			.param("user", identity.userId())
			.param("provider", provider)
			.param("subject", profile.subject())
			.param("email", profile.email())
			.param("verified", profile.emailVerified())
			.param("login", profile.login())
			.param("now", Timestamp.from(now))
			.update();
	}

	void updateIdentity(String id, ProviderProfile profile, Instant now) {
		this.jdbc.sql("""
				UPDATE identities SET email = :email, email_verified = :verified, login = :login, last_login_at = :now
				WHERE id = :id""")
			.param("id", id)
			.param("email", profile.email())
			.param("verified", profile.emailVerified())
			.param("login", profile.login())
			.param("now", Timestamp.from(now))
			.update();
	}

	boolean workspaceExists(String id) {
		return this.jdbc.sql("SELECT count(*) FROM workspaces WHERE id = :id")
			.param("id", id)
			.query(Integer.class)
			.single() > 0;
	}

	/**
	 * Makes someone an owner of a workspace, whether or not they were a member.
	 * @param workspaceId the workspace
	 * @param userId the person
	 * @param now the time, if they join now
	 */
	void makeOwner(String workspaceId, String userId, Instant now) {
		this.jdbc.sql("""
				INSERT INTO memberships (workspace_id, user_id, role, created_at)
				VALUES (:workspace, :user, 'owner', :now)
				ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = 'owner'""")
			.param("workspace", workspaceId)
			.param("user", userId)
			.param("now", Timestamp.from(now))
			.update();
	}

}
