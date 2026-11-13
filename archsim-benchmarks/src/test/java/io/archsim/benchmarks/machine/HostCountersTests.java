package io.archsim.benchmarks.machine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PowerShell's script, encoded so that Windows' command line leaves its quotes alone.
 */
class HostCountersTests {

	@Test
	void encoded_aScript_isBase64OfItsUtf16le() {
		String script = "$c = @('\\Processor(_Total)\\% Time'); \"done\"";

		String encoded = HostCounters.encoded(script);

		assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE)).isEqualTo(script);
	}

}
