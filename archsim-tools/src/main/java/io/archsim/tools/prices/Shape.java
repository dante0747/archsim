package io.archsim.tools.prices;

import io.archsim.engine.numeric.PythonRound;

/**
 * An instance type's vCPUs and memory in GiB. A snapshot keeps Python's whole numbers and
 * floats apart: Azure's sizes and GCP's machine types are whole ({@code 4}), AWS's
 * shapes, read from a price list's text, are floats ({@code 4.0}).
 *
 * @param vcpu the vCPUs, an {@link Integer} or a {@link Double}
 * @param memoryGib the memory in GiB, an {@link Integer} or a {@link Double}
 */
record Shape(Number vcpu, Number memoryGib) {

	private static final int MEMORY_DECIMALS = 3;

	Shape {
		requireWholeOrFloat(vcpu);
		requireWholeOrFloat(memoryGib);
	}

	/**
	 * A shape in whole numbers, as Azure's sizes and GCP's machine types have.
	 * @param vcpu the vCPUs
	 * @param memoryGib the memory in GiB
	 * @return the shape
	 */
	static Shape whole(int vcpu, int memoryGib) {
		return new Shape(vcpu, memoryGib);
	}

	/**
	 * A shape in floats, as AWS's price lists give them.
	 * @param vcpu the vCPUs
	 * @param memoryGib the memory in GiB
	 * @return the shape
	 */
	static Shape of(double vcpu, double memoryGib) {
		return new Shape(vcpu, memoryGib);
	}

	/**
	 * This shape with its memory rounded to three decimals, as Python's {@code round()}
	 * rounds a float; whole memory stays as it is.
	 * @return the rounded shape
	 */
	Shape rounded() {
		if (this.memoryGib instanceof Double memory) {
			return new Shape(this.vcpu, PythonRound.round(memory, MEMORY_DECIMALS));
		}
		return this;
	}

	private static void requireWholeOrFloat(Number number) {
		if (!(number instanceof Integer) && !(number instanceof Double)) {
			throw new IllegalArgumentException("neither an Integer nor a Double: " + number.getClass().getName());
		}
	}

}
