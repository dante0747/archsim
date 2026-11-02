package io.archsim.model.codec;

import java.util.ArrayList;
import java.util.List;

/** The errors found while binding one input, in the order they were found. */
final class Errors {

	private final List<BindingError> found = new ArrayList<>();

	void add(String type, Location at, String message) {
		found.add(new BindingError(type, at, message));
	}

	/** A mark to ask later whether anything went wrong since. */
	int mark() {
		return found.size();
	}

	boolean since(int mark) {
		return found.size() > mark;
	}

	boolean isEmpty() {
		return found.isEmpty();
	}

	List<BindingError> list() {
		return List.copyOf(found);
	}

}
