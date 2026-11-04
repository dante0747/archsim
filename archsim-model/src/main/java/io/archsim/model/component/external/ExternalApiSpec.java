package io.archsim.model.component.external;

import java.util.List;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentIdentity;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.Port;

/**
 * A third-party service you call but do not operate (payments, email, LLM inference...):
 * each call takes the provider's response time, may fail, and may be refused by its
 * limits.
 */
public final class ExternalApiSpec extends ComponentSpec<ExternalApiConfig> {

	/** The component type's name in documents. */
	public static final String TYPE = "external_api";

	/** Creates the external API type: calls come in, and nothing goes out. */
	public ExternalApiSpec() {
		super(new ComponentIdentity(TYPE, "External API", Category.EXTERNAL), ExternalApiConfig.class,
				List.of(Port.in("in", "Calls", "Calls to the third-party service.")));
	}

}
