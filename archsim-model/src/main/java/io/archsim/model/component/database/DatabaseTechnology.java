package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/**
 * The database product; its behaviour (locks, commits, replication, reads) follows its
 * profile.
 */
public enum DatabaseTechnology implements JsonEnum {

	POSTGRESQL("postgresql"), MYSQL("mysql"), MARIADB("mariadb"), SQLSERVER("sqlserver"), ORACLE("oracle"),
	COCKROACHDB("cockroachdb"), YUGABYTEDB("yugabytedb"), MONGODB("mongodb"), CASSANDRA("cassandra"),
	SCYLLADB("scylladb"), DYNAMODB("dynamodb"), COUCHBASE("couchbase"), ELASTICSEARCH("elasticsearch"),
	OPENSEARCH("opensearch"), GENERIC("generic");

	private final String json;

	DatabaseTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
