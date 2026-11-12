package io.archsim.cli;

import java.math.BigInteger;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.model.text.PythonText;

/**
 * A whole-number option read as argparse's {@code type=int} read it, through Python's
 * {@code int()} ({@link PythonNumbers#parseInt(String)}): spaces around it, digits of any
 * script, a sign, underscores between digits and leading zeros, of any size up to the
 * 4,300 digits Python converts from text. Anything else, a float or a hexadecimal number
 * included, is refused, as Python refused it, as a usage error.
 */
final class PythonInt implements ITypeConverter<BigInteger> {

	@Override
	public BigInteger convert(String value) {
		try {
			return PythonNumbers.parseInt(value);
		}
		catch (NumberFormatException ex) {
			throw new TypeConversionException("invalid int value: " + PythonText.repr(value));
		}
	}

}
