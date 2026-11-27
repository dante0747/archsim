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
 * {@link Visitors} and {@link Principals}.
 */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration implements WebMvcConfigurer {

	private final Visitors visitors;

	private final Principals principals;

	AccessConfiguration(ArchsimProperties properties, ObjectProvider<Clock> clock,
			ObjectProvider<SecurityContextHolderStrategy> contexts) {
		this.visitors = new Visitors(properties, clock.getIfAvailable(Clock::systemUTC));
		this.principals = new Principals(this.visitors.clientIps(),
				contexts.getIfAvailable(SecurityContextHolder::getContextHolderStrategy));
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

	/**
	 * Who is asking, as the controllers' parameters read it, for what runs before them:
	 * the request slots count a person's requests to the engine before the body is read.
	 * @return the principals
	 */
	@Bean
	Principals principals() {
		return this.principals;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new PrincipalResolver(this.principals));
		resolvers.add(new VisitorResolver(this.visitors));
		resolvers.add(new RequestedWorkspaceResolver());
	}

}
