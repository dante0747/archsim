package io.archsim.app.engine;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * Where the Python engine service listens ({@code ARCHSIM_ENGINE_URL}) and how long the
 * API waits for it. Kept apart from the API's own settings, because the service goes away
 * in stage 4 of the port (ADR-0045).
 *
 * @param url the service's base URL
 * @param timeout how long one engine call may take, such as the replay of a long
 * calibration; in seconds ({@code ARCHSIM_ENGINE_TIMEOUT=120}), like the other durations
 */
@ConfigurationProperties("archsim.engine")
public record EngineServiceProperties(@DefaultValue("http://127.0.0.1:8001") URI url,
		@DefaultValue("120") @DurationUnit(ChronoUnit.SECONDS) Duration timeout) {

}
