package io.archsim.benchmarks.docker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

/**
 * The services' endpoints as a test plays them: each request is recorded, a POST as
 * {@code POST <url>}, and answered by the test.
 */
public final class FakeServices implements ServiceHttp {

	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

	private final Function<String, JsonNode> answers;

	/**
	 * Services answering each request.
	 * @param answers the answer to a request ({@code GET <url>} or {@code POST <url>})
	 */
	public FakeServices(Function<String, JsonNode> answers) {
		this.answers = answers;
	}

	@Override
	public JsonNode get(String url) {
		return answer("GET " + url);
	}

	@Override
	public JsonNode post(String url) {
		return answer("POST " + url);
	}

	/**
	 * The requests made so far.
	 * @return each one, {@code GET <url>} or {@code POST <url>}, in order
	 */
	public List<String> requests() {
		return List.copyOf(this.requests);
	}

	private JsonNode answer(String request) {
		this.requests.add(request);
		return this.answers.apply(request);
	}

}
