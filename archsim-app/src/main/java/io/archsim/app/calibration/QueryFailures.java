package io.archsim.app.calibration;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;

import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.MalformedChunkCodingException;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.TruncatedChunkException;
import org.jspecify.annotations.Nullable;

import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.model.text.PythonFormat;

/**
 * Why a Prometheus query failed, as the Python engine's {@code _get} and {@code _reason}
 * word it: too late, or a reason that carries nothing the server sent. The operating
 * system's reason is matched to the words Python shows, never repeated.
 */
final class QueryFailures {

	/**
	 * The operating system's reasons, as the JDK words them, and as Python's
	 * {@code strerror} words them on Linux.
	 */
	private static final Map<String, String> SOCKET_REASONS = Map.of("network is unreachable", "Network is unreachable",
			"no route to host", "No route to host", "connection reset", "Connection reset by peer", "broken pipe",
			"Broken pipe", "host is down", "Host is down", "connection timed out", "Connection timed out");

	private QueryFailures() {
	}

	/**
	 * A query that failed, as the Python engine refuses it.
	 * @param server the server, its URL and deadline
	 * @param failure how the query failed, before any answer could be used
	 * @param pastDeadline whether the deadline had passed
	 * @return the refusal: too late when the deadline passed or a wait timed out, else
	 * the reason it could not be made
	 */
	static PrometheusException failed(PrometheusServer server, IOException failure, boolean pastDeadline) {
		List<Throwable> causes = causes(failure);
		if (pastDeadline || has(causes, InterruptedIOException.class)) {
			return new PrometheusException(server.url() + " did not answer within "
					+ PythonFormat.format(server.timeoutSeconds(), "g") + " s");
		}
		return new PrometheusException("cannot query " + server.url() + ": " + reason(causes));
	}

	/**
	 * Why a query failed.
	 * @param causes how it failed, its causes after it
	 * @return the reason
	 */
	private static String reason(List<Throwable> causes) {
		String http = httpReason(causes);
		if (http != null) {
			return http;
		}
		if (has(causes, UnknownHostException.class)) {
			return "unknown host";
		}
		if (has(causes, SSLPeerUnverifiedException.class) || has(causes, CertificateException.class)) {
			return "its TLS certificate is not valid for this host";
		}
		if (has(causes, SSLException.class)) {
			return "the TLS handshake failed";
		}
		return socketReason(causes);
	}

	/**
	 * An answer cut off, missing or not HTTP.
	 * @param causes how the query failed
	 * @return the reason; {@code null} when the answer was not at fault
	 */
	private static @Nullable String httpReason(List<Throwable> causes) {
		if (has(causes, ConnectionClosedException.class) || has(causes, TruncatedChunkException.class)) {
			return "the response was cut off";
		}
		if (has(causes, NoHttpResponseException.class)) {
			return "the server closed the connection";
		}
		for (Throwable cause : causes) {
			if (cause instanceof HttpException || cause instanceof MalformedChunkCodingException
					|| cause instanceof MessageConstraintException) {
				return "not a valid HTTP response (" + cause.getClass().getSimpleName() + ")";
			}
		}
		return null;
	}

	/**
	 * A refused connection, or the operating system's reason a socket failed, else the
	 * failure's kind, as Python's {@code type(exc).__name__}.
	 * @param causes how the query failed
	 * @return the reason
	 */
	private static String socketReason(List<Throwable> causes) {
		Throwable root = causes.getLast();
		if (root instanceof SocketException) {
			String message = String.valueOf(root.getMessage()).toLowerCase(Locale.ROOT);
			if (root instanceof ConnectException && message.startsWith("connection refused")) {
				return "connection refused";
			}
			for (Map.Entry<String, String> reason : SOCKET_REASONS.entrySet()) {
				if (message.startsWith(reason.getKey())) {
					return reason.getValue();
				}
			}
		}
		return has(causes, ConnectException.class) ? "connection refused" : root.getClass().getSimpleName();
	}

	private static List<Throwable> causes(Throwable failure) {
		List<Throwable> causes = new ArrayList<>();
		for (Throwable cause = failure; cause != null && !causes.contains(cause); cause = cause.getCause()) {
			causes.add(cause);
		}
		return causes;
	}

	private static boolean has(List<Throwable> causes, Class<? extends Throwable> kind) {
		return causes.stream().anyMatch(kind::isInstance);
	}

}
