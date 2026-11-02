package io.archsim.model.component.external;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.Port;
import java.util.List;

/**
 * A third-party service you call but do not operate (payments, email, LLM inference...): each call
 * takes the provider's response time, may fail, and may be refused by its limits.
 */
public final class ExternalApiSpec extends ComponentSpec<ExternalApiConfig> {

    public ExternalApiSpec() {
        super(
                "external_api",
                "External API",
                Category.EXTERNAL,
                ExternalApiConfig.class,
                List.of(Port.in("in", "Calls", "Calls to the third-party service.")));
    }
}
