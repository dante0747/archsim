package io.archsim.app.signin;

/**
 * Who signed in with a provider, as Spring Security's OAuth2 login hands it to
 * {@link Callbacks}: the user its user service read, with the profile ArchSim keeps.
 */
interface ProviderAccount {

	/**
	 * Who signed in, as the provider says.
	 * @return the profile
	 */
	ProviderProfile profile();

}
