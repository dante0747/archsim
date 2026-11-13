package io.archsim.benchmarks.docker;

import tools.jackson.databind.JsonNode;

/**
 * The HTTP endpoints of a stack's services, which answer in JSON: the service k6 calls
 * ({@link #API}) and Kafka's consumer ({@link #CONSUMER}), each with {@code /health},
 * {@code /stats} and {@code /reset}.
 */
public interface ServiceHttp {

	/** The service under test, as the host reaches it. */
	String API = "http://127.0.0.1:18080";

	/** Kafka's consumer, as the host reaches it. */
	String CONSUMER = "http://127.0.0.1:18081";

	/**
	 * What an endpoint answers to a GET.
	 * @param url the endpoint
	 * @return its answer
	 * @throws java.io.UncheckedIOException when it does not answer, or answers with an
	 * error
	 */
	JsonNode get(String url);

	/**
	 * What an endpoint answers to a POST without a body.
	 * @param url the endpoint
	 * @return its answer
	 * @throws java.io.UncheckedIOException when it does not answer, or answers with an
	 * error
	 */
	JsonNode post(String url);

}
