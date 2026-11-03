package io.archsim.app.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes every point in time in the API's JSON as pydantic writes a UTC datetime:
 * {@code 2026-11-03T01:02:03.123456Z}, with six digits of microseconds, or none when
 * there are none ({@code 2026-11-03T01:02:03Z}).
 */
@JacksonComponent
public final class PydanticTimes extends ValueSerializer<Instant> {

	private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

	private static final int NANOS_PER_MICRO = 1_000;

	/**
	 * A point in time as pydantic writes it.
	 * @param instant the point in time
	 * @return its text, in UTC
	 */
	public static String format(Instant instant) {
		OffsetDateTime utc = instant.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
		int micros = utc.getNano() / NANOS_PER_MICRO;
		return utc.format(SECONDS) + ((micros != 0) ? ".%06d".formatted(micros) : "") + "Z";
	}

	@Override
	public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
		generator.writeString(format(value));
	}

}
