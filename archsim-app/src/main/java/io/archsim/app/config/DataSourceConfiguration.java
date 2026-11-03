package io.archsim.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The connection pool to the database {@code ARCHSIM_DATABASE_URL} names. Its pool
 * settings are Spring Boot's {@code spring.datasource.hikari.*}.
 */
@Configuration(proxyBeanMethods = false)
class DataSourceConfiguration {

	@Bean
	@ConfigurationProperties("spring.datasource.hikari")
	HikariDataSource dataSource(ArchsimProperties properties) {
		DatabaseUrl url = DatabaseUrl.parse(properties.databaseUrl());
		return DataSourceBuilder.create()
			.type(HikariDataSource.class)
			.url(url.jdbcUrl())
			.username(url.username())
			.password(url.password())
			.build();
	}

}
