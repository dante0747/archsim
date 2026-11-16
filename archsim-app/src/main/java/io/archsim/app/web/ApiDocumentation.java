package io.archsim.app.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.archsim.engine.version.ArchsimVersion;

/**
 * What the OpenAPI document says of the API as a whole: its name, ArchSim's version, what
 * it is, its license and where it is served. Springdoc works out the rest, the paths and
 * schemas, from the controllers.
 */
@Configuration(proxyBeanMethods = false)
class ApiDocumentation {

	private static final String TITLE = "ArchSim API";

	private static final String DESCRIPTION = """
			The HTTP API of ArchSim, an open-source simulator for distributed systems: validate and \
			simulate architecture documents, keep them as projects with their runs and reports, and compare \
			runs. Results are estimates, not production guarantees. Every number comes from the model and \
			the assumptions it is given. `GET %s/meta` names the versions this server runs (ArchSim's, the \
			engine's, the document schema's and the report's) and its limits. The guide, with sign-in, \
			workspaces, tokens and errors, is at [archsim.io/docs/api](https://archsim.io/docs/api/).\
			""".formatted(WebConfiguration.PREFIX);

	private static final String LICENSE = "AGPL-3.0-only";

	private static final String LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html";

	private static final String CONTACT = "ArchSim";

	private static final String REPOSITORY = "https://github.com/dante0747/archsim";

	@Bean
	OpenAPI apiDocument() {
		Info info = new Info().title(TITLE)
			.version(ArchsimVersion.PRODUCT)
			.description(DESCRIPTION)
			.license(new License().name(LICENSE).url(LICENSE_URL))
			.contact(new Contact().name(CONTACT).url(REPOSITORY));
		return new OpenAPI().info(info).addServersItem(origin());
	}

	/**
	 * A relative server, which a client resolves against the document's own URL: the root
	 * of whatever origin serves it. Every path is absolute, under
	 * {@value WebConfiguration#PREFIX}, and the web server's same-origin proxy forwards
	 * every path under {@code /api} unchanged, so "Try it out" calls the API through the
	 * web server, or the API itself when it is opened directly. Springdoc would name the
	 * host the request reached instead, which behind the proxy is an address inside the
	 * deployment that no browser can reach.
	 * @return the server, {@code /}, with no host of its own
	 */
	private static Server origin() {
		return new Server().url("/").description("The origin these docs are served from");
	}

}
