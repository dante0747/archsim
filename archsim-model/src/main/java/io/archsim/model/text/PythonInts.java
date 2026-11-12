package io.archsim.model.text;

/**
 * Python's limit on whole numbers written as text.
 */
public final class PythonInts {

	/**
	 * The most decimal digits Python's {@code int()} reads from text
	 * ({@code sys.int_info.default_max_str_digits}): its guard against conversions that
	 * take quadratic time. Leading zeros count; underscores do not.
	 */
	public static final int MAX_STR_DIGITS = 4300;

	private PythonInts() {
	}

}
