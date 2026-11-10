package io.archsim.cli;

/**
 * A command that answers an error nobody expected itself, in one line and with an exit
 * code of its own, where another command's would be a bug, reported with its stack trace.
 */
interface AnswersUnexpectedErrors {

	/**
	 * Answers an error the command did not expect: an exception picocli caught, or an
	 * error of the JVM's that ended the command.
	 * @param unexpected the error
	 * @return the exit code
	 */
	int answerUnexpected(Throwable unexpected);

}
