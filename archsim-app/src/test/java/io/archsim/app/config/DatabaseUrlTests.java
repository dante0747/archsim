package io.archsim.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The database ARCHSIM_DATABASE_URL names, as a plain URL, SQLAlchemy's or JDBC's. */
class DatabaseUrlTests {

	@Test
	void parse_postgresqlUrl_givesTheJdbcUrlAndCredentials() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://archsim:s3cret@postgres:5432/archsim");

		assertThat(url).isEqualTo(new DatabaseUrl("jdbc:postgresql://postgres:5432/archsim", "archsim", "s3cret"));
	}

	@Test
	void parse_postgresScheme_givesTheJdbcUrlAndCredentials() {
		DatabaseUrl url = DatabaseUrl.parse("postgres://archsim:s3cret@postgres:5432/archsim");

		assertThat(url).isEqualTo(new DatabaseUrl("jdbc:postgresql://postgres:5432/archsim", "archsim", "s3cret"));
	}

	@Test
	void parse_sqlAlchemyUrl_givesTheJdbcUrlAndCredentials() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql+psycopg://archsim:s3cret@postgres:5432/archsim");

		assertThat(url).isEqualTo(new DatabaseUrl("jdbc:postgresql://postgres:5432/archsim", "archsim", "s3cret"));
	}

	@Test
	void parse_urlWithoutPort_usesPostgresqlsDefault() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://db.example/archsim");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example:5432/archsim");
	}

	@Test
	void parse_percentEncodedPassword_isDecodedKeepingPlus() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://user:p%40ss+word@host/db");

		assertThat(url.password()).isEqualTo("p@ss+word");
	}

	@Test
	void parse_urlWithQuery_keepsTheParameters() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://host:6543/db?sslmode=require");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://host:6543/db?sslmode=require");
	}

	@Test
	void parse_jdbcUrl_isUsedAsItIs() {
		DatabaseUrl url = DatabaseUrl.parse(" jdbc:postgresql://localhost:5432/db?user=postgres ");

		assertThat(url).isEqualTo(new DatabaseUrl("jdbc:postgresql://localhost:5432/db?user=postgres", null, null));
	}

	@Test
	void parse_sqliteUrl_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> DatabaseUrl.parse("sqlite:///./archsim.db"))
			.withMessageContaining("PostgreSQL");
	}

	@Test
	void parse_passwordWithSpaceAndHash_isTakenAsSqlAlchemyTakesIt() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql+psycopg://archsim:s3cret pass#1@db:5432/archsim");

		assertThat(url).isEqualTo(new DatabaseUrl("jdbc:postgresql://db:5432/archsim", "archsim", "s3cret pass#1"));
	}

	@Test
	void parse_hostWithUnderscore_isTheHost() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://u:p@archsim_db:5432/archsim");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://archsim_db:5432/archsim");
	}

	@Test
	void parse_ipv6Host_keepsItsBrackets() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://u:p@[::1]:5432/archsim");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://[::1]:5432/archsim");
	}

	@Test
	void parse_percentThatStartsNoEscape_staysAsItIs() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://u:100%zz@host/db");

		assertThat(url.password()).isEqualTo("100%zz");
	}

	@Test
	void parse_percentEncodedSocketDirectory_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DatabaseUrl.parse("postgresql://u:s3cret@%2Fvar%2Frun%2Fpostgresql/archsim"))
			.withMessageContaining("host")
			.withMessageNotContaining("s3cret");
	}

	@Test
	void parse_urlWithoutHost_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> DatabaseUrl.parse("postgresql://u:s3cret@/archsim"))
			.withMessageContaining("host")
			.withMessageNotContaining("s3cret");
	}

	@Test
	void parse_emptyHostInAList_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DatabaseUrl.parse("postgresql://u:s3cret@primary,/archsim"))
			.withMessageContaining("host")
			.withMessageNotContaining("s3cret");
	}

	@Test
	void parse_listOfIpv6Hosts_isRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> DatabaseUrl.parse("postgresql://u:s3cret@[::1],[::2]:6543/archsim"))
			.withMessageContaining("host")
			.withMessageNotContaining("s3cret");
	}

	@Test
	void parse_hostsWithoutPorts_areEachAHostOfTheJdbcUrl() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://u:p@primary,standby/archsim");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://primary:5432,standby:5432/archsim");
	}

	@Test
	void parse_hostsWithOnePort_giveEachHostThePort() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://u:p@primary,standby:6543/archsim");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://primary:6543,standby:6543/archsim");
	}

	@Test
	void parse_badPort_failsWithoutRepeatingTheUrl() {
		assertThatIllegalArgumentException().isThrownBy(() -> DatabaseUrl.parse("postgresql://u:s3cret@host:x/db"))
			.withMessageContaining("port")
			.withMessageNotContaining("s3cret");
	}

	@Test
	void parse_notPostgresql_failsWithoutRepeatingTheUrl() {
		assertThatIllegalArgumentException().isThrownBy(() -> DatabaseUrl.parse("mysql://u:s3cret@host/db"))
			.withMessageNotContaining("s3cret");
	}

	@Test
	void toString_withPassword_masksIt() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://archsim:s3cret@db/archsim");

		assertThat(url)
			.hasToString("DatabaseUrl[jdbcUrl=jdbc:postgresql://db:5432/archsim, username=archsim, password=***]");
	}

	@Test
	void masked_passwordInTheUserPart_isHidden() {
		String masked = DatabaseUrl.masked("postgresql+psycopg://archsim:s3cret pass@db/archsim");

		assertThat(masked).isEqualTo("postgresql+psycopg://archsim:***@db/archsim");
	}

	@Test
	void masked_passwordParameter_isHidden() {
		String masked = DatabaseUrl.masked("jdbc:postgresql://db/archsim?user=a&password=s3cret&ssl=true");

		assertThat(masked).isEqualTo("jdbc:postgresql://db/archsim?user=a&password=***&ssl=true");
	}

}
