package io.archsim.engine.calibration;

/**
 * Hours, minutes, seconds and a fraction, as Python 3.12's C {@code parse_hh_mm_ss_ff}
 * reads them, for a time of day or an offset.
 *
 * @param ending how they end
 * @param hour the hours
 * @param minute the minutes
 * @param second the seconds
 * @param fraction the microseconds
 */
record IsoClock(Ending ending, int hour, int minute, int second, int fraction) {

	static final IsoClock FAILED = new IsoClock(Ending.FAILED, 0, 0, 0, 0);

	static final IsoClock MIDNIGHT = new IsoClock(Ending.CLEAN, 0, 0, 0, 0);

	private static final int FIELDS = 3;

	private static final int FIELD_DIGITS = 2;

	private static final int MICRO_DIGITS = 6;

	private static final int DECIMAL = 10;

	private static final int HOURS = 24;

	private static final int MINUTES = 60;

	private static final int SECONDS_PER_HOUR = 3600;

	private static final long MICROS_PER_SECOND = 1_000_000;

	/**
	 * Reads the fields: two digits each, separated by colons if the first two are, then a
	 * fraction after a dot or a comma. As in the C code, a fraction may follow the hours,
	 * and digits after a colon following the seconds are read as one.
	 * @param text the text
	 * @param start where they start
	 * @param end where they end: the offset, or the text's end
	 * @return the fields
	 */
	static IsoClock read(IsoBytes text, int start, int end) {
		int[] fields = new int[FIELDS];
		int pos = start;
		boolean colons = text.at(start + FIELD_DIGITS) == ':';
		for (int index = 0; index < fields.length; index++) {
			fields[index] = text.digits(pos, FIELD_DIGITS);
			if (fields[index] < 0) {
				return FAILED;
			}
			int next = text.at(pos + FIELD_DIGITS);
			pos += FIELD_DIGITS + 1;
			if (pos >= end) {
				return of(Ending.before(next), fields, 0);
			}
			if (!(colons && next == ':')) {
				if (next == '.' || next == ',') {
					break;
				}
				if (colons) {
					return FAILED;
				}
				pos--;
			}
		}
		return fraction(text, pos, end, fields);
	}

	/**
	 * The fraction, up to six digits and any more skipped; there is at least one byte
	 * before the end.
	 * @param text the text
	 * @param start where it starts
	 * @param end where the fields end
	 * @param fields the hours, minutes and seconds
	 * @return the fields
	 */
	private static IsoClock fraction(IsoBytes text, int start, int end, int[] fields) {
		int count = Math.min(end - start, MICRO_DIGITS);
		int micros = text.digits(start, count);
		if (micros < 0) {
			return FAILED;
		}
		for (int digit = count; digit < MICRO_DIGITS; digit++) {
			micros *= DECIMAL;
		}
		int pos = start + count;
		while (IsoBytes.isDigit(text.at(pos))) {
			pos++;
		}
		return of(Ending.before(text.at(pos)), fields, micros);
	}

	private static IsoClock of(Ending ending, int[] fields, int micros) {
		return new IsoClock(ending, fields[0], fields[1], fields[2], micros);
	}

	/**
	 * The fields as seconds, unchecked, as an offset's are.
	 * @return the seconds
	 */
	int seconds() {
		return this.hour * SECONDS_PER_HOUR + this.minute * MINUTES + this.second;
	}

	/**
	 * The time of day in microseconds, its fields checked as the C code's
	 * {@code check_time_args} checks them.
	 * @return the microseconds since midnight
	 * @throws IllegalArgumentException in Python's words when a field is out of range
	 */
	long micros() {
		if (this.hour >= HOURS) {
			throw new IllegalArgumentException("hour must be in 0..23");
		}
		if (this.minute >= MINUTES) {
			throw new IllegalArgumentException("minute must be in 0..59");
		}
		if (this.second >= MINUTES) {
			throw new IllegalArgumentException("second must be in 0..59");
		}
		return seconds() * MICROS_PER_SECOND + this.fraction;
	}

	/** How fields end. */
	enum Ending {

		/** They are not a time. */
		FAILED,

		/** At the end. */
		CLEAN,

		/** Before more text, which is an error unless an offset follows. */
		TRAILING;

		/**
		 * How fields end before a byte.
		 * @param next the byte after them
		 * @return clean at the end, else trailing
		 */
		static Ending before(int next) {
			return (next == 0) ? CLEAN : TRAILING;
		}

	}

}
