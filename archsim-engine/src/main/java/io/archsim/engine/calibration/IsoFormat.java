package io.archsim.engine.calibration;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

import org.jspecify.annotations.Nullable;

import io.archsim.model.text.PythonText;

/**
 * A time read as Python 3.12's {@code datetime.fromisoformat} reads it, in its C
 * implementation: a date ({@code YYYY-MM-DD}, {@code YYYYMMDD}, {@code YYYY-Www[-D]} or
 * {@code YYYYWww[D]}), then, after any one character, a time ({@code HH[:MM[:SS]]} or
 * {@code HHMMSS}, a fraction after {@code .} or {@code ,} cut to microseconds) and an
 * offset ({@code Z}, or a sign and a time). It reads the text's UTF-8 bytes as the C code
 * does, with its quirks: a fraction may follow the hour, and a fourth field after a colon
 * is read as one. Errors are Python's words.
 */
final class IsoFormat {

	/** A UTF-8 lead byte's mask and bits, by the width of the character it starts. */
	private static final int[][] UTF8_LEADS = { { 0x80, 0x00 }, { 0xE0, 0xC0 }, { 0xF0, 0xE0 }, { 0xF8, 0xF0 } };

	private static final int YEAR_DIGITS = 4;

	private static final int SHORT_DATE = 8;

	private static final int DASHED_DATE = 10;

	private static final int WEEK_DAY_DASH = 8;

	private static final int MAX_YEAR = 9999;

	private static final int MONTHS = 12;

	private static final int WEEKS = 52;

	private static final int DAYS_IN_WEEK = 7;

	/** The last weekday, Thursday, whose week is still a year's first week. */
	private static final int LAST_FIRST_WEEK_DAY = 3;

	private static final long MICROS_PER_SECOND = 1_000_000;

	private static final long MICROS_PER_DAY = 86_400 * MICROS_PER_SECOND;

	private IsoFormat() {
	}

	/**
	 * A time in microseconds since the epoch; UTC when it has no offset.
	 * @param text the time, as {@code fromisoformat} is given it
	 * @return its microseconds since the epoch
	 * @throws IllegalArgumentException in Python's words when it is not one, its fields
	 * are out of range, or its offset is a day or more
	 */
	static long epochMicros(String text) {
		IsoBytes bytes = IsoBytes.of(text);
		if (bytes == null) {
			throw invalid(text);
		}
		int separator = separator(bytes);
		CalendarDate date = (separator >= 0) ? date(bytes, separator) : null;
		if (date == null) {
			throw invalid(text);
		}
		Time time = Time.MIDNIGHT;
		if (bytes.length() > separator) {
			int skip = width(bytes.at(separator));
			Time read = (skip > 0) ? time(bytes, separator + skip) : null;
			if (read == null) {
				throw invalid(text);
			}
			time = read;
		}
		long offset = offsetMicros(time.offset());
		LocalDate day = date.checked();
		return day.toEpochDay() * MICROS_PER_DAY + time.clock().micros() - offset;
	}

	private static IllegalArgumentException invalid(String text) {
		return new IllegalArgumentException("Invalid isoformat string: " + PythonText.repr(text));
	}

	/**
	 * Where the date ends, told by its fifth and sixth bytes, as the C code's
	 * {@code _find_isoformat_datetime_separator} tells it.
	 * @param text the text
	 * @return the separator's index; {@code -1} for none
	 */
	private static int separator(IsoBytes text) {
		if (text.length() == IsoBytes.SHORTEST) {
			return IsoBytes.SHORTEST;
		}
		if (text.at(YEAR_DIGITS) == '-') {
			return (text.at(YEAR_DIGITS + 1) == 'W') ? dashedWeekSeparator(text) : DASHED_DATE;
		}
		return (text.at(YEAR_DIGITS) == 'W') ? weekSeparator(text) : SHORT_DATE;
	}

	/**
	 * After {@code YYYY-Www}: a dash then a digit is the day ({@code YYYY-Www-D}), unless
	 * another digit follows, when the dash is the separator.
	 * @param text the text
	 * @return the separator's index; {@code -1} for none
	 */
	private static int dashedWeekSeparator(IsoBytes text) {
		int length = text.length();
		if (length <= WEEK_DAY_DASH || text.at(WEEK_DAY_DASH) != '-') {
			return SHORT_DATE;
		}
		if (length == WEEK_DAY_DASH + 1) {
			return -1;
		}
		return (length > DASHED_DATE && IsoBytes.isDigit(text.at(DASHED_DATE))) ? SHORT_DATE : DASHED_DATE;
	}

	/**
	 * After {@code YYYYWww}: the digits that follow say whether a day comes first.
	 * @param text the text
	 * @return the separator's index
	 */
	private static int weekSeparator(IsoBytes text) {
		int index = IsoBytes.SHORTEST;
		while (index < text.length() && IsoBytes.isDigit(text.at(index))) {
			index++;
		}
		if (index <= SHORT_DATE) {
			return index;
		}
		return (index % 2 == 0) ? IsoBytes.SHORTEST : SHORT_DATE;
	}

	/**
	 * The date before the separator, as {@code parse_isoformat_date} reads it.
	 * @param text the text
	 * @param length where the separator is
	 * @return the date as written; {@code null} when it is no date
	 */
	private static @Nullable CalendarDate date(IsoBytes text, int length) {
		int year = text.digits(0, YEAR_DIGITS);
		if (year < 0) {
			return null;
		}
		boolean dashed = text.at(YEAR_DIGITS) == '-';
		int pos = dashed ? YEAR_DIGITS + 1 : YEAR_DIGITS;
		if (text.at(pos) == 'W') {
			return weekDate(text, new WeekStart(year, pos + 1, dashed), length);
		}
		int month = text.digits(pos, 2);
		pos += 2;
		if (month < 0 || !text.dashAt(pos, dashed)) {
			return null;
		}
		int day = text.digits(dashed ? pos + 1 : pos, 2);
		return (day < 0) ? null : new CalendarDate(year, month, day);
	}

	/**
	 * An ISO week date's week and day, when the date has room for a day.
	 * @param text the text
	 * @param start the year and where the week number starts
	 * @param length where the separator is
	 * @return the date as written; {@code null} when it is no date
	 */
	private static @Nullable CalendarDate weekDate(IsoBytes text, WeekStart start, int length) {
		int week = text.digits(start.at(), 2);
		if (week < 0) {
			return null;
		}
		int pos = start.at() + 2;
		int day = 1;
		if (pos < length) {
			if (!text.dashAt(pos, start.dashed())) {
				return null;
			}
			day = text.digits(start.dashed() ? pos + 1 : pos, 1);
		}
		return (day < 0) ? null : gregorian(start.year(), week, day);
	}

	/**
	 * An ISO week date as a calendar date, as the C code's {@code iso_to_ymd} counts it.
	 * @param year the ISO year
	 * @param week its week
	 * @param day the day of the week, from Monday as 1
	 * @return the calendar date; {@code null} when the week date is not one
	 */
	private static @Nullable CalendarDate gregorian(int year, int week, int day) {
		if (year < 1 || year > MAX_YEAR) {
			return null;
		}
		LocalDate first = LocalDate.of(year, 1, 1);
		if (week < 1 || week > weeks(first) || day < 1 || day > DAYS_IN_WEEK) {
			return null;
		}
		int sinceMonday = first.getDayOfWeek().getValue() - 1;
		LocalDate monday = first.minusDays(sinceMonday)
			.plusDays((sinceMonday > LAST_FIRST_WEEK_DAY) ? DAYS_IN_WEEK : 0);
		LocalDate date = monday.plusDays((week - 1L) * DAYS_IN_WEEK + day - 1);
		return new CalendarDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
	}

	/**
	 * How many weeks an ISO year has: 53 when it starts on a Thursday, or on a Wednesday
	 * in a leap year.
	 * @param first the year's first day
	 * @return 52 or 53
	 */
	private static int weeks(LocalDate first) {
		DayOfWeek weekday = first.getDayOfWeek();
		boolean leapWednesday = weekday == DayOfWeek.WEDNESDAY && first.isLeapYear();
		return (weekday == DayOfWeek.THURSDAY || leapWednesday) ? WEEKS + 1 : WEEKS;
	}

	/**
	 * The time after the separator and its offset, as {@code parse_isoformat_time} reads
	 * them.
	 * @param text the text
	 * @param start where the time starts
	 * @return the time; {@code null} when it is no time
	 */
	private static @Nullable Time time(IsoBytes text, int start) {
		int end = text.length();
		int zone = zoneStart(text, start, end);
		IsoClock clock = IsoClock.read(text, start, zone);
		if (clock.ending() == IsoClock.Ending.FAILED) {
			return null;
		}
		if (zone == end) {
			return (clock.ending() == IsoClock.Ending.CLEAN) ? new Time(clock, null) : null;
		}
		if (text.at(zone) == 'Z') {
			return (text.at(zone + 1) == 0) ? new Time(clock, new Offset(0, 0)) : null;
		}
		int sign = (text.at(zone) == '-') ? -1 : 1;
		IsoClock offset = IsoClock.read(text, zone + 1, end);
		if (offset.ending() != IsoClock.Ending.CLEAN) {
			return null;
		}
		return new Time(clock, new Offset(sign * offset.seconds(), sign * offset.fraction()));
	}

	/**
	 * Where the offset starts: the first {@code Z}, {@code +} or {@code -}.
	 * @param text the text
	 * @param start where the time starts
	 * @param end where the text ends
	 * @return the offset's index; past the end when there is none
	 */
	private static int zoneStart(IsoBytes text, int start, int end) {
		int zone = start;
		while (!isZoneMark(text.at(zone))) {
			zone++;
			if (zone >= end) {
				break;
			}
		}
		return zone;
	}

	private static boolean isZoneMark(int at) {
		return at == 'Z' || at == '+' || at == '-';
	}

	/**
	 * An offset in microseconds, as the C code makes a {@code timezone} of it: an offset
	 * of no whole seconds is UTC, whatever its fraction.
	 * @param offset the offset; {@code null} for none, read as UTC
	 * @return its microseconds
	 * @throws IllegalArgumentException in Python's words when it is a day or more
	 */
	private static long offsetMicros(@Nullable Offset offset) {
		if (offset == null || offset.seconds() == 0) {
			return 0;
		}
		long micros = offset.seconds() * MICROS_PER_SECOND + offset.fraction();
		if (micros <= -MICROS_PER_DAY || micros >= MICROS_PER_DAY) {
			throw new IllegalArgumentException("offset must be a timedelta strictly between -timedelta(hours=24) and "
					+ "timedelta(hours=24), not " + timedelta(micros) + ".");
		}
		return micros;
	}

	/**
	 * A span as Python's {@code repr} of a {@code timedelta} writes it.
	 * @param micros the span
	 * @return such as {@code datetime.timedelta(days=1, seconds=3600)}
	 */
	private static String timedelta(long micros) {
		long days = Math.floorDiv(micros, MICROS_PER_DAY);
		long rest = Math.floorMod(micros, MICROS_PER_DAY);
		StringBuilder parts = new StringBuilder();
		appendPart(parts, "days", days);
		appendPart(parts, "seconds", rest / MICROS_PER_SECOND);
		appendPart(parts, "microseconds", rest % MICROS_PER_SECOND);
		return "datetime.timedelta(" + (parts.isEmpty() ? "0" : parts) + ")";
	}

	private static void appendPart(StringBuilder parts, String name, long value) {
		if (value != 0) {
			parts.append(parts.isEmpty() ? "" : ", ").append(name).append('=').append(value);
		}
	}

	/**
	 * How many bytes the UTF-8 character starting with a byte takes, as the C code skips
	 * the separator.
	 * @param lead its first byte
	 * @return its width; {@code 0} when it starts no character
	 */
	private static int width(int lead) {
		for (int bytes = 0; bytes < UTF8_LEADS.length; bytes++) {
			if ((lead & UTF8_LEADS[bytes][0]) == UTF8_LEADS[bytes][1]) {
				return bytes + 1;
			}
		}
		return 0;
	}

	/**
	 * A date as written, its fields not yet checked.
	 *
	 * @param year the year
	 * @param month the month
	 * @param day the day of the month
	 */
	private record CalendarDate(int year, int month, int day) {

		/**
		 * The date, its fields in range as the C code's {@code check_date_args} checks
		 * them.
		 * @return the date
		 * @throws IllegalArgumentException in Python's words when a field is out of range
		 */
		LocalDate checked() {
			if (this.year < 1 || this.year > MAX_YEAR) {
				throw new IllegalArgumentException("year " + this.year + " is out of range");
			}
			if (this.month < 1 || this.month > MONTHS) {
				throw new IllegalArgumentException("month must be in 1..12");
			}
			if (this.day < 1 || this.day > YearMonth.of(this.year, this.month).lengthOfMonth()) {
				throw new IllegalArgumentException("day is out of range for month");
			}
			return LocalDate.of(this.year, this.month, this.day);
		}

	}

	/**
	 * A week date's year and where its week number starts.
	 *
	 * @param year the ISO year
	 * @param at where the week number starts
	 * @param dashed whether the date's fields are separated by dashes
	 */
	private record WeekStart(int year, int at, boolean dashed) {
	}

	/**
	 * An offset from UTC, unchecked.
	 *
	 * @param seconds its whole seconds, signed
	 * @param fraction its microseconds, signed
	 */
	private record Offset(int seconds, int fraction) {
	}

	/**
	 * A time of day and its offset.
	 *
	 * @param clock the time of day
	 * @param offset the offset; {@code null} for none
	 */
	private record Time(IsoClock clock, @Nullable Offset offset) {

		static final Time MIDNIGHT = new Time(IsoClock.MIDNIGHT, null);

	}

}
