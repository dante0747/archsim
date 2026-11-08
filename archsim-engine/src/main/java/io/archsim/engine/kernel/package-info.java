/**
 * The simulation kernel: simulated time and its events, processed one at a time in SimPy
 * 4.1.2's order, with SimPy's processes, conditions, resources and stores, so a component
 * written as steps between events runs as the Python engine's coroutine ran.
 */
@NullMarked
package io.archsim.engine.kernel;

import org.jspecify.annotations.NullMarked;
