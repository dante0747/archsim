package io.archsim.engine.imports;

import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A YAML timestamp as the text Python's {@code str()} gives the {@code date} or
 * {@code datetime} PyYAML builds from it ({@code 2001-12-14 21:59:43.100000-05:00}), with
 * Python's words for one that is no date or time.
 */
final class PythonTimestamps {

	private static final Pattern TIMESTAMP = Pattern
		.compile("(?<year>[0-9]{4})-(?<month>[0-9][0-9]?)-(?<day>[0-9][0-9]?)"
				+ "(?:(?:[Tt]|[ \\t]+)(?<hour>[0-9][0-9]?):(?<minute>[0-9][0-9]):(?<second>[0-9][0-9])"
				+ "(?:\\.(?<fraction>[0-9]*))?"
				+ "(?:[ \\t]*(?<zone>Z|(?<sign>[-+])(?<zoneHour>[0-9][0-9]?)(?::(?<zoneMinute>[0-9][0-9]))?))?)?\\n?");

	/** Digits of a fraction of a second Python keeps: microseconds. */
	private static final int MICROSECOND_DIGITS = 6;

	private static final int LAST_MONTH = 12;

	private static final int LAST_HOUR = 23;

	private static final int LAST_MINUTE = 59;

	private static final int MINUTES_PER_HOUR = 60;

	private static final int SECONDS_PER_MINUTE = 60;

	private static final int SECONDS_PER_DAY = 86_400;

	/** A day in minutes: a UTC offset must be strictly within it. */
	private static final int MINUTES_PER_DAY = 1440;

	private PythonTimestamps() {
	}

	/**
	 * A timestamp scalar as Python's {@code str()} writes what PyYAML builds from it.
	 * @param scalar the scalar, which PyYAML's timestamp resolver matched
	 * @return {@code 2024-05-01}, {@code 2002-12-14 01:02:03+00:00}, ...
	 * @throws ImportRefusedException with Python's words when it is no date or time
	 * @throws ImportShapeException in Python's words when it is no timestamp at all
	 */
	static String text(String scalar) {
		Matcher timestamp = TIMESTAMP.matcher(scalar);
		if (!timestamp.matches()) {
			// PyYAML matches the scalar after reading it as a timestamp: one that is none
			// fails
			throw new ImportShapeException("'NoneType' object has no attribute 'groupdict'");
		}
		String date = "%04d-%02d-%02d".formatted(number(timestamp, "year"), number(timestamp, "month"),
				number(timestamp, "day"));
		if (timestamp.group("hour") == null) {
			checkDate(timestamp);
			return date;
		}
		String offset = offset(timestamp);
		checkDate(timestamp);
		checkTime(timestamp);
		String time = "%02d:%02d:%02d".formatted(number(timestamp, "hour"), number(timestamp, "minute"),
				number(timestamp, "second"));
		return date + " " + time + fraction(timestamp.group("fraction")) + offset;
	}

	private static void checkDate(Matcher timestamp) {
		int year = number(timestamp, "year");
		int month = number(timestamp, "month");
		int day = number(timestamp, "day");
		if (year < 1) {
			throw new ImportRefusedException("year " + year + " is out of range");
		}
		if (month < 1 || month > LAST_MONTH) {
			throw new ImportRefusedException("month must be in 1..12");
		}
		if (day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
			throw new ImportRefusedException("day is out of range for month");
		}
	}

	private static void checkTime(Matcher timestamp) {
		if (number(timestamp, "hour") > LAST_HOUR) {
			throw new ImportRefusedException("hour must be in 0..23");
		}
		if (number(timestamp, "minute") > LAST_MINUTE) {
			throw new ImportRefusedException("minute must be in 0..59");
		}
		if (number(timestamp, "second") > LAST_MINUTE) {
			throw new ImportRefusedException("second must be in 0..59");
		}
	}

	/**
	 * A fraction of a second as Python writes it after the time.
	 * @param digits the fraction's digits, as written
	 * @return {@code .100000}, its microseconds; nothing for none
	 */
	private static String fraction(@Nullable String digits) {
		if (digits == null || digits.isEmpty()) {
			return "";
		}
		String micros = (digits + "0".repeat(MICROSECOND_DIGITS)).substring(0, MICROSECOND_DIGITS);
		return (Integer.parseInt(micros) == 0) ? "" : "." + micros;
	}

	/**
	 * The UTC offset as Python writes it after the time.
	 * @param timestamp the matched timestamp
	 * @return {@code +00:00} for {@code Z}, {@code -05:00}, ...; nothing for a local time
	 * @throws ImportRefusedException with Python's words for an offset of a day or more
	 */
	private static String offset(Matcher timestamp) {
		String sign = timestamp.group("sign");
		if (sign == null) {
			return (timestamp.group("zone") != null) ? "+00:00" : "";
		}
		String zoneMinute = timestamp.group("zoneMinute");
		int minutes = number(timestamp, "zoneHour") * MINUTES_PER_HOUR
				+ ((zoneMinute != null) ? Integer.parseInt(zoneMinute) : 0);
		if (minutes >= MINUTES_PER_DAY) {
			throw new ImportRefusedException("offset must be a timedelta strictly between -timedelta(hours=24) and "
					+ "timedelta(hours=24), not " + timedelta("-".equals(sign) ? -minutes : minutes) + ".");
		}
		if (minutes == 0) {
			return "+00:00";
		}
		return sign + "%02d:%02d".formatted(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR);
	}

	/**
	 * Python's {@code repr()} of a timedelta.
	 * @param minutes the timedelta, in whole minutes
	 * @return {@code datetime.timedelta(days=1, seconds=3600)}
	 */
	private static String timedelta(int minutes) {
		int seconds = minutes * SECONDS_PER_MINUTE;
		int days = Math.floorDiv(seconds, SECONDS_PER_DAY);
		int rest = Math.floorMod(seconds, SECONDS_PER_DAY);
		if (rest == 0) {
			return "datetime.timedelta(days=" + days + ")";
		}
		return (days == 0) ? "datetime.timedelta(seconds=" + rest + ")"
				: "datetime.timedelta(days=" + days + ", seconds=" + rest + ")";
	}

	private static int number(Matcher timestamp, String group) {
		return Integer.parseInt(timestamp.group(group));
	}

}
