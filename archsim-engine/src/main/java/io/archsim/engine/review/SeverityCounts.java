package io.archsim.engine.review;

import java.util.List;

/**
 * How many findings a review has of each severity.
 *
 * @param high the findings of high severity
 * @param medium those of medium severity
 * @param low those of low severity
 */
public record SeverityCounts(int high, int medium, int low) {

	/**
	 * The counts of some findings.
	 * @param findings the findings
	 * @return how many there are of each severity
	 */
	public static SeverityCounts of(List<Finding> findings) {
		return new SeverityCounts(count(findings, Severity.HIGH), count(findings, Severity.MEDIUM),
				count(findings, Severity.LOW));
	}

	private static int count(List<Finding> findings, Severity severity) {
		return (int) findings.stream().filter((finding) -> finding.severity() == severity).count();
	}

}
