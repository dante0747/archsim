/**
 * CPU and memory inputs fitted from what a part used over a range of load, as the Python
 * engine's {@code calibration/resources.py} fits them: {@code cpuShare} and CPU per
 * request against load, how that curve continues above its top rate, and the working set
 * against the request rate and requests held.
 */
@NullMarked
package io.archsim.engine.calibration.resources;

import org.jspecify.annotations.NullMarked;
