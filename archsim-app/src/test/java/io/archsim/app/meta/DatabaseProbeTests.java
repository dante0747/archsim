package io.archsim.app.meta;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the database answers, on a real PostgreSQL and on a port nothing listens on.
 */
class DatabaseProbeTests {

	@Test
	void answers_runningDatabase_isTrue() {
		DatabaseProbe probe = new DatabaseProbe(
				JdbcClient.create(new DriverManagerDataSource(TestDatabases.freshJdbcUrl())));

		assertThat(probe.answers()).isTrue();
	}

	@Test
	void answers_nothingListening_isFalse() throws IOException {
		DatabaseProbe probe = new DatabaseProbe(
				JdbcClient.create(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:" + freePort() + "/none")));

		assertThat(probe.answers()).isFalse();
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}
