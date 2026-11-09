/**
 * Calibration from telemetry ({@code POST /calibrate}): archsim-engine's Prometheus and
 * OpenTelemetry calibrations, whose replay the Java simulator runs in the API's process
 * (ADR-0053), and the Prometheus client they query, under the server's policy for calling
 * Prometheus servers ({@code ARCHSIM_OUTBOUND}; ADR-0046).
 */
@NullMarked
package io.archsim.app.calibration;

import org.jspecify.annotations.NullMarked;
