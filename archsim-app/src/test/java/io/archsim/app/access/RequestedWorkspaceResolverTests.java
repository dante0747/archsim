package io.archsim.app.access;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import io.archsim.app.web.RequestInvalidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

/** The workspace a request names, as FastAPI read {@code ?workspace=} and the header. */
class RequestedWorkspaceResolverTests {

	private static final MethodParameter PARAMETER = mock();

	private final RequestedWorkspaceResolver resolver = new RequestedWorkspaceResolver();

	@Test
	void resolveArgument_queryAndHeader_isTheQuerys() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addParameter("workspace", "fromQuery");
		request.addHeader("X-ArchSim-Workspace", "fromHeader");

		assertThat(resolve(request)).isEqualTo(new RequestedWorkspace("fromQuery"));
	}

	@Test
	void resolveArgument_headerOnly_isTheHeaders() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-ArchSim-Workspace", "fromHeader");

		assertThat(resolve(request)).isEqualTo(new RequestedWorkspace("fromHeader"));
	}

	@Test
	void resolveArgument_neither_isTheAskersOwn() {
		assertThat(resolve(new MockHttpServletRequest())).isEqualTo(new RequestedWorkspace(null));
	}

	@Test
	void resolveArgument_repeatedQuery_isTheLastAsFastApiReadsIt() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addParameter("workspace", "first", "last");

		assertThat(resolve(request)).isEqualTo(new RequestedWorkspace("last"));
	}

	@Test
	void resolveArgument_thirtyTwoCharactersOutsideTheBasicPlane_isAccepted() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addParameter("workspace", "😀".repeat(32));

		assertThat(resolve(request).id()).hasSize(64);
	}

	@Test
	void resolveArgument_queryOverThirtyTwoCharacters_isRefused() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addParameter("workspace", "w".repeat(33));

		assertThatExceptionOfType(RequestInvalidException.class).isThrownBy(() -> resolve(request));
	}

	private RequestedWorkspace resolve(MockHttpServletRequest request) {
		return this.resolver.resolveArgument(PARAMETER, null, new ServletWebRequest(request), null);
	}

}
