package io.archsim.app.engine;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client of the Python engine service.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EngineServiceProperties.class)
class EngineConfiguration {

	/** How long connecting to the service may take: it runs next to the API. */
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	EngineService engineService(EngineServiceProperties properties) {
		// HTTP/1.1: an upgrade to HTTP/2 loses the request's body on the way to uvicorn
		HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(CONNECT_TIMEOUT)
			.build();
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
		requests.setReadTimeout(properties.timeout());
		return new EngineService(
				RestClient.builder().baseUrl(properties.url().toString()).requestFactory(requests).build());
	}

}
