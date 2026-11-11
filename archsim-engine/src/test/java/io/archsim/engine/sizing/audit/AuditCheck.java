package io.archsim.engine.sizing.audit;

/**
 * One criterion's outcome on one part of a template's run.
 *
 * @param part the part checked, or {@code *} for the whole design
 * @param criterion the criterion: cpu, bottleneck, memory, rerun, monotonic, per-request
 * or cost
 * @param ok whether it held
 * @param detail what was measured against what
 */
record AuditCheck(String part, String criterion, boolean ok, String detail) {

	@Override
	public String toString() {
		return this.criterion + " " + this.part + ": " + this.detail;
	}

}
