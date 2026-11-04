package io.archsim.engine.pricing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.pricing.SkuCategory;

import static org.assertj.core.api.Assertions.assertThat;

/** Which price list a part's machines come from. */
class MachineKindTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest(name = "a {0} with {1} runs on {2} ({3})")
	@CsvSource(delimiter = '|', nullValues = "-", textBlock = """
			service|{}|COMPUTE|-
			database|{}|DATABASE|postgresql
			database|{"technology": "mysql"}|DATABASE|mysql
			cache|{}|CACHE|redis
			message_queue|{"technology": "kafka"}|STREAM|kafka
			message_queue|{"technology": "rabbitmq"}|COMPUTE|-
			load_balancer|{}|COMPUTE|-
			""")
	void of_aPartBilledPerMachine_isItsPriceList(String type, String config, SkuCategory category, String engine) {
		assertThat(MachineKind.of(type, LenientSettings.of(JSON.readTree(config))))
			.hasValue(new MachineKind(category, engine));
	}

	@ParameterizedTest(name = "a {0} with {1} is not billed per machine")
	@CsvSource(delimiter = '|', textBlock = """
			service|{"platform": "kubernetes"}
			service|{"platform": "serverless"}
			database|{"technology": "dynamodb"}
			load_balancer|{"technology": "aws_alb"}
			traffic_source|{}
			external_api|{}
			""")
	void of_aPartNotBilledPerMachine_isNone(String type, String config) {
		assertThat(MachineKind.of(type, LenientSettings.of(JSON.readTree(config)))).isEmpty();
	}

}
