package io.archsim.app.signin;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.config.SignInProperties;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Times;
import io.archsim.app.workspaces.Workspaces;

/**
 * Finds or creates the person behind a provider account. The account is matched by the
 * provider's id; a new one whose verified email belongs to someone already here is linked
 * to them (an unverified email is never trusted); otherwise a new person is created with
 * a personal workspace. A new person whose verified email is in
 * {@code ARCHSIM_ADMIN_EMAILS} also becomes an owner of the default workspace.
 */
@Service
class Accounts {

	private static final Logger LOGGER = LoggerFactory.getLogger(Accounts.class);

	private final AccountRepository repository;

	private final Workspaces workspaces;

	private final SignInProperties settings;

	private final Clock clock;

	Accounts(AccountRepository repository, Workspaces workspaces, SignInProperties settings, Clock clock) {
		this.repository = repository;
		this.workspaces = workspaces;
		this.settings = settings;
		this.clock = clock;
	}

	/**
	 * Signs a provider account in, all or nothing.
	 * @param provider {@code github} or {@code google}
	 * @param profile who the provider says signed in
	 * @return the person's user id
	 * @throws SignInFailure when the person's account is disabled
	 */
	@Transactional
	String signIn(String provider, ProviderProfile profile) {
		Instant now = Times.now(this.clock);
		Optional<Identity> identity = this.repository.identity(provider, profile.subject());
		Optional<Account> known = identity.flatMap((found) -> this.repository.account(found.userId()))
			.or(() -> verifiedEmail(profile).flatMap(this.repository::accountWithVerifiedEmail));
		Account account = known.orElseGet(() -> create(profile, now));
		if (account.disabled()) {
			throw SignInFailure.disabled();
		}
		identity.ifPresentOrElse((found) -> this.repository.updateIdentity(found.id(), profile, now),
				() -> this.repository.addIdentity(new Identity(Ids.newId(), account.id()), provider, profile, now));
		this.repository.updateUser(account.id(), profile, now);
		if (known.isEmpty() && verifiedEmail(profile).filter(this.settings::isAdmin).isPresent()
				&& this.repository.workspaceExists(Access.DEFAULT_WORKSPACE_ID)) {
			this.repository.makeOwner(Access.DEFAULT_WORKSPACE_ID, account.id(), now);
			LOGGER.info("{} signed in for the first time and owns the default workspace", account.id());
		}
		return account.id();
	}

	private Account create(ProviderProfile profile, Instant now) {
		Account account = new Account(Ids.newId(), false);
		this.repository.addUser(account.id(), profile, now);
		String[] words = profile.name().strip().split("(?U)\\s+", -1);
		String first = words[0].isEmpty() ? "My" : words[0];
		this.workspaces.create(first + "'s workspace", Workspaces.PERSONAL, account.id());
		return account;
	}

	private static Optional<String> verifiedEmail(ProviderProfile profile) {
		return profile.emailVerified() ? Optional.ofNullable(profile.email()) : Optional.empty();
	}

}
