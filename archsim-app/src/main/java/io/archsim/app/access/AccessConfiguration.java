package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
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

	private final ObjectProvider<Csrf> csrf;

	private final SecurityContextHolderStrategy contexts;

	AccessConfiguration(ArchsimProperties properties, ObjectProvider<Clock> clock, ObjectProvider<Csrf> csrf,
			ObjectProvider<SecurityContextHolderStrategy> contexts) {
		this.properties = properties;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
		this.csrf = csrf;
		this.contexts = contexts.getIfAvailable(SecurityContextHolder::getContextHolderStrategy);
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		ClientIps clientIps = new ClientIps(new TrustedProxies(this.properties.trustedProxies().hosts(), this.clock));
		resolvers.add(new PrincipalResolver(clientIps, this.csrf, this.contexts));
		resolvers.add(new VisitorResolver(clientIps));
		resolvers.add(new RequestedWorkspaceResolver());
	}

}
