package io.archsim.app.web;

import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat reads a path as Starlette does: an encoded slash ({@code %2F}) is a slash, so
 * {@code /api/v1/a%2Fb} is a route that does not exist ({@code 404} with the API's body)
 * rather than a request Tomcat refuses with its own page. No endpoint decides anything
 * from the raw path, so decoding it opens nothing.
 */
@Configuration(proxyBeanMethods = false)
class TomcatConfiguration {

	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> encodedSlashes() {
		return (factory) -> factory.addConnectorCustomizers(
				(connector) -> connector.setEncodedSolidusHandling(EncodedSolidusHandling.DECODE.getValue()));
	}

}
