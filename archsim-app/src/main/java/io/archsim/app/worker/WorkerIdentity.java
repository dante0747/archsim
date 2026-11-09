package io.archsim.app.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

import io.archsim.app.store.Text;

/**
 * Who a worker is, in {@code job_workers} and in the leases it holds: its host, its
 * process and a random suffix, as the Python worker named itself, so two workers never
 * share a name.
 *
 * @param id the worker's name, at most 64 characters
 * @param hostname the machine it runs on, at most 255 characters
 * @param pid its process
 */
record WorkerIdentity(String id, String hostname, long pid) {

	/** The characters of the host's name the id keeps. */
	private static final int HOST_IN_ID = 32;

	/** The longest host name {@code job_workers} holds. */
	private static final int MAX_HOSTNAME = 255;

	/** The random bytes that end the id, as hex. */
	private static final int SUFFIX_BYTES = 4;

	/**
	 * This process, as a worker with a name of its own.
	 * @return its identity
	 */
	static WorkerIdentity ofThisProcess() {
		String hostname = localHostName();
		long pid = ProcessHandle.current().pid();
		byte[] suffix = new byte[SUFFIX_BYTES];
		ThreadLocalRandom.current().nextBytes(suffix);
		String id = Text.cut(hostname, HOST_IN_ID) + "-" + pid + "-" + HexFormat.of().formatHex(suffix);
		return new WorkerIdentity(id, Text.cut(hostname, MAX_HOSTNAME), pid);
	}

	private static String localHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			return "localhost";
		}
	}

}
