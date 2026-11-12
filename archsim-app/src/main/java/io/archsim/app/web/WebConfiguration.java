package io.archsim.app.web;

import java.util.List;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.ArchsimProperties;

/**
 * How Spring MVC serves the API as FastAPI did (ADR-0034): every controller under
 * {@value #PREFIX}, JSON whatever the request accepts, {@link ApiBody} parameters read by
 * the model's codec, and browsers on the configured origins allowed to read its answers,
 * with Starlette's CORS settings and leniency.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

	/** The path every endpoint is under; breaking changes would go to {@code /api/v2}. */
	public static final String PREFIX = "/api/v1";

	/** How long a browser may cache a preflight answer, in seconds, as Starlette says. */
	private static final long PREFLIGHT_MAX_AGE = 600;

	private final ArchsimProperties properties;

	private final JsonMapper json;

	WebConfiguration(ArchsimProperties properties, JsonMapper json) {
		this.properties = properties;
		this.json = json;
	}

	@Override
	public void configurePathMatch(PathMatchConfigurer configurer) {
		configurer.addPathPrefix(PREFIX, HandlerTypePredicate.forBasePackage("io.archsim.app")
			.and((type) -> !ErrorController.class.isAssignableFrom(type)));
	}

	@Override
	public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
		configurer.ignoreAcceptHeader(true).defaultContentType(MediaType.APPLICATION_JSON);
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new ApiBodyResolver(this.json));
	}

	/**
	 * Binds one text to a {@code List<String>} whole and as given, where Spring would
	 * split it at its commas, in every binding of Spring MVC ({@link WholeQueryValue}).
	 * @param registry the converters Spring MVC binds parameters with
	 */
	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(new WholeQueryValue());
	}

	/**
	 * CORS on every request, as Starlette's middleware wraps every request: what no
	 * endpoint answers (a route that does not exist) is checked too. Spring Security's
	 * chain runs this filter by its bean name, {@code corsFilter}, so that its own
	 * refusals carry the CORS headers; under another name the chain would fall back to
	 * Spring MVC's CORS settings, which have none. Spring Boot also registers it with the
	 * servlet container under the same name, a second pass that does nothing, as the
	 * filter runs once a request.
	 * @return the filter, with Starlette's settings and leniency
	 */
	@Bean
	CorsFilter corsFilter() {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(this.properties.corsOrigins().origins());
		cors.addAllowedMethod(CorsConfiguration.ALL);
		cors.addAllowedHeader(CorsConfiguration.ALL);
		cors.setMaxAge(PREFLIGHT_MAX_AGE);
		UrlBasedCorsConfigurationSource everywhere = new UrlBasedCorsConfigurationSource();
		everywhere.registerCorsConfiguration("/**", cors);
		CorsFilter filter = new CorsFilter(everywhere);
		filter.setCorsProcessor(new LenientCorsProcessor());
		return filter;
	}

}
