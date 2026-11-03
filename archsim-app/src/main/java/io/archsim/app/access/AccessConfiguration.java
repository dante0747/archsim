package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.archsim.app.config.ArchsimProperties;

/**
 * Lets a controller take the request's {@link Principal} and {@link RequestedWorkspace}
 * as parameters.
 */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

	private final ArchsimProperties properties;

	private final Clock clock;

	AccessConfiguration(ArchsimProperties properties, ObjectProvider<Clock> clock) {
		this.properties = properties;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		TrustedProxies proxies = new TrustedProxies(this.properties.trustedProxies().hosts(), this.clock);
		resolvers.add(new PrincipalResolver(this.properties, proxies));
		resolvers.add(new RequestedWorkspaceResolver());
	}

}
