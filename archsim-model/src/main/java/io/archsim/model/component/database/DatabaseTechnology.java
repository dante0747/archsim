package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/**
 * The database product; its behaviour (locks, commits, replication, reads) follows its
 * profile.
 */
public enum DatabaseTechnology implements JsonEnum {

	/** PostgreSQL, a relational database. */
	POSTGRESQL("postgresql"),
	/** MySQL, a relational database. */
	MYSQL("mysql"),
	/** MariaDB, a relational database forked from MySQL. */
	MARIADB("mariadb"),
	/** Microsoft SQL Server, a relational database whose reads wait for writers. */
	SQLSERVER("sqlserver"),
	/** Oracle Database, a relational database. */
	ORACLE("oracle"),
	/** CockroachDB, a distributed SQL database. */
	COCKROACHDB("cockroachdb"),
	/** YugabyteDB, a distributed SQL database. */
	YUGABYTEDB("yugabytedb"),
	/** MongoDB, a document database. */
	MONGODB("mongodb"),
	/** Apache Cassandra, a wide-column store. */
	CASSANDRA("cassandra"),
	/** ScyllaDB, a wide-column store compatible with Cassandra. */
	SCYLLADB("scylladb"),
	/** Amazon DynamoDB, a key-value store metered in capacity units. */
	DYNAMODB("dynamodb"),
	/** Couchbase, a document database. */
	COUCHBASE("couchbase"),
	/** Elasticsearch, a search engine whose reads fan out to every shard. */
	ELASTICSEARCH("elasticsearch"),
	/** OpenSearch, Elasticsearch's open-source fork. */
	OPENSEARCH("opensearch"),
	/** A database of no particular product. */
	GENERIC("generic");

	private final String json;

	DatabaseTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
