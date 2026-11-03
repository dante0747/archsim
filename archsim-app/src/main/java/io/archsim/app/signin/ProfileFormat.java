package io.archsim.app.signin;

import tools.jackson.databind.JsonNode;

/**
 * How a provider describes the account that signed in.
 */
enum ProfileFormat {

	/** GitHub's {@code /user}, with the emails from {@code /user/emails}. */
	GITHUB {

		@Override
		ProviderProfile read(JsonNode account, JsonNode emails) {
			return ProviderProfile.github(account, emails);
		}

	},

	/** OpenID Connect's {@code userinfo}, as Google answers it. */
	GOOGLE {

		@Override
		ProviderProfile read(JsonNode account, JsonNode emails) {
			return ProviderProfile.google(account);
		}

	};

	/**
	 * Who signed in.
	 * @param account what the provider says about the account
	 * @param emails the account's emails, when the provider lists them apart
	 * @return the profile
	 * @throws SignInFailure when the account has no id
	 */
	abstract ProviderProfile read(JsonNode account, JsonNode emails);

}
