package io.archsim.engine.review;

/**
 * What a finding says.
 *
 * @param title what it is, in a line
 * @param detail why it matters, with the design's numbers
 * @param suggestion what to do about it
 */
record Wording(String title, String detail, String suggestion) {

}
