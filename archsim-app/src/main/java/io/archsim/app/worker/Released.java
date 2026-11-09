package io.archsim.app.worker;

/**
 * What became of a run its worker gave back unfinished, as it stopped.
 */
enum Released {

	/** Queued again, its attempt not counted. */
	QUEUED,

	/** Cancelled, as someone had asked. */
	CANCELLED

}
