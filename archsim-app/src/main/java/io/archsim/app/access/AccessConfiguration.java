package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.archsim.app.config.ArchsimProperties;

/**
 * Lets a controller take the request's {@link Principal}, {@link Visitor} and
 * {@link RequestedWorkspace} as parameters.
 */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

	private final ArchsimProperties properties;

	private final Clock clock;

	private final ObjectProvider<Credentials> credentials;

	private final ObjectProvider<Csrf> csrf;

	AccessConfiguration(ArchsimProperties properties, ObjectProvider<Clock> clock,
			ObjectProvider<Credentials> credentials, ObjectProvider<Csrf> csrf) {
		this.properties = properties;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
		this.credentials = credentials;
		this.csrf = csrf;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		ClientIps clientIps = new ClientIps(new TrustedProxies(this.properties.trustedProxies().hosts(), this.clock));
		resolvers.add(new PrincipalResolver(this.properties, clientIps, this.credentials, this.csrf));
		resolvers.add(new VisitorResolver(clientIps));
		resolvers.add(new RequestedWorkspaceResolver());
	}

}
