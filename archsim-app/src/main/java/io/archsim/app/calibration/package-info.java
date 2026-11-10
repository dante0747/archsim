/**
 * Calibration from telemetry ({@code POST /calibrate}): archsim-engine's Prometheus and
 * OpenTelemetry calibrations, whose replay the Java simulator runs in the API's process
 * (ADR-0053), and the server's policy for calling the Prometheus servers they query
 * ({@code ARCHSIM_OUTBOUND}; ADR-0046), under which the engine's client calls them
 * (ADR-0057).
 */
@NullMarked
package io.archsim.app.calibration;

import org.jspecify.annotations.NullMarked;
