package io.archsim.apitests;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.opentest4j.TestAbortedException;

/**
 * Finds (or starts) the servers of {@link ApiTest} classes and hands them to test
 * methods.
 */
public final class ApiExtension implements BeforeAllCallback, ParameterResolver {

	private static final Namespace NAMESPACE = Namespace.create(ApiExtension.class);

	@Override
	public void beforeAll(ExtensionContext context) {
		server(context, profileOf(context.getRequiredTestClass()));
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		Class<?> type = parameter.getParameter().getType();
		return type == ApiServer.class || type == ApiClient.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		Profile profile = parameter.findAnnotation(On.class)
			.map(On::value)
			.orElseGet(() -> profileOf(context.getRequiredTestClass()));
		ApiServer server = server(context, profile);
		Class<?> type = parameter.getParameter().getType();
		if (type == ApiServer.class) {
			return server;
		}
		if (type == ApiClient.class) {
			return server.client();
		}
		throw new ParameterResolutionException("not an API parameter: " + type);
	}

	/**
	 * Returns the test run's servers, shared by every class and stopped when the run
	 * ends.
	 * @param context the context of any test or class of the run
	 * @return the servers, created on first use
	 */
	static ApiServers servers(ExtensionContext context) {
		return context.getRoot()
			.getStore(NAMESPACE)
			.computeIfAbsent(ApiServers.class, (key) -> new ApiServers(SuiteConfig.fromSystemProperties()),
					ApiServers.class);
	}

	private static ApiServer server(ExtensionContext context, Profile profile) {
		ApiServer server = servers(context).server(profile);
		if (server == null) {
			throw new TestAbortedException("no " + profile.id() + " server in this run: set archsim.api.url."
					+ profile.id() + ", or archsim.api.start=python");
		}
		return server;
	}

	private static Profile profileOf(Class<?> testClass) {
		for (Class<?> type = testClass; type != null; type = enclosing(type)) {
			ApiTest annotation = type.getAnnotation(ApiTest.class);
			if (annotation != null) {
				return annotation.value();
			}
		}
		throw new IllegalStateException(testClass + " is not an @ApiTest");
	}

	private static @Nullable Class<?> enclosing(Class<?> type) {
		return type.getEnclosingClass();
	}

}
