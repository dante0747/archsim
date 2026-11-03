package io.archsim.app.config;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * ArchSim's settings for a test, bound as the application binds them: the defaults, with
 * the settings the test names.
 */
public final class TestProperties {

	private TestProperties() {
	}

	/**
	 * The settings, with some of them given.
	 * @param settings {@code name=value} pairs under {@code archsim.}, such as
	 * {@code max-concurrent-runs=3}
	 * @return the settings
	 */
	public static ArchsimProperties with(String... settings) {
		return bind(ArchsimProperties.class, settings);
	}

	/**
	 * The sign-in settings, with some of them given.
	 * @param settings {@code name=value} pairs under {@code archsim.}, such as
	 * {@code secret-key=...}
	 * @return the settings
	 */
	public static SignInProperties signIn(String... settings) {
		return bind(SignInProperties.class, settings);
	}

	private static <T> T bind(Class<T> type, String... settings) {
		Map<String, String> given = Arrays.stream(settings)
			.map((setting) -> setting.split("=", 2))
			.collect(Collectors.toMap((pair) -> "archsim." + pair[0], (pair) -> pair[1]));
		return new Binder(new MapConfigurationPropertySource(given)).bindOrCreate("archsim", type);
	}

}
