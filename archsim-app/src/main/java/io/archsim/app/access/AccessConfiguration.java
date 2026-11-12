package io.archsim.app.access;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.archsim.app.config.ArchsimProperties;

/**
 * Lets a controller take the request's {@link Principal}, {@link Visitor} and
 * {@link RequestedWorkspace} as parameters, and gives code outside Spring MVC the same
 * {@link Visitors}.
 */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

	private final Visitors visitors;

	private final SecurityContextHolderStrategy contexts;

	AccessConfiguration(ArchsimProperties properties, ObjectProvider<Clock> clock,
			ObjectProvider<SecurityContextHolderStrategy> contexts) {
		this.visitors = new Visitors(properties, clock.getIfAvailable(Clock::systemUTC));
		this.contexts = contexts.getIfAvailable(SecurityContextHolder::getContextHolderStrategy);
	}

	/**
	 * Where requests come from, the same the controllers' parameters read, for the
	 * handlers of Spring Security's sign-in.
	 * @return the visitors
	 */
	@Bean
	Visitors visitors() {
		return this.visitors;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new PrincipalResolver(this.visitors.clientIps(), this.contexts));
		resolvers.add(new VisitorResolver(this.visitors));
		resolvers.add(new RequestedWorkspaceResolver());
	}

}
