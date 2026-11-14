package io.archsim.model.pricing;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Const;
import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.ModelName;

/**
 * A price snapshot file as archsim-tools' price refresh writes it (format
 * {@code archsim.prices/1}), read with the Python engine's rules so a bad file is refused
 * with the same reason.
 *
 * @param format always {@value #FORMAT}
 * @param provider the cloud provider, such as {@code aws}
 * @param region the region, such as {@code us-east-1}
 * @param currency always {@code USD}
 * @param snapshotDate the day the prices were read, {@code YYYY-MM-DD}
 * @param sources where the prices came from
 * @param note a remark, such as the provider's offer file versions
 * @param compute virtual machine types
 * @param databases managed database types
 * @param caches managed cache types
 * @param streams managed stream types
 * @param rates per-unit prices
 */
@ModelName("SnapshotModel")
record SnapshotFile(@Const(FORMAT) String format, @Matches(NAME) String provider, @Matches(NAME) String region,
		@Default("USD") @Const("USD") String currency, @Matches("^\\d{4}-\\d{2}-\\d{2}$") String snapshotDate,
		@MinItems(1) List<PriceSource> sources, @MaxLength(500) @Nullable String note, @Default("[]") List<Sku> compute,
		@Default("[]") List<Sku> databases, @Default("[]") List<Sku> caches, @Default("[]") List<Sku> streams,
		@Default("{}") Rates rates) {

	/** The format every snapshot file names. */
	static final String FORMAT = "archsim.prices/1";

	/** A provider or region, such as {@code aws} or {@code us-east-1}. */
	static final String NAME = "^[a-z0-9-]{1,40}$";

	/**
	 * Copies the lists, so the file's record does not change.
	 */
	SnapshotFile {
		sources = List.copyOf(sources);
		compute = List.copyOf(compute);
		databases = List.copyOf(databases);
		caches = List.copyOf(caches);
		streams = List.copyOf(streams);
	}

	/**
	 * A snapshot's per-unit prices; each is optional, since older snapshots have fewer.
	 *
	 * @param storageGibMonth block storage (EBS gp3), per GiB-month
	 * @param serverlessGbSecond functions (Lambda), per GB-second
	 * @param serverlessPerMillionRequests functions (Lambda), per million invocations
	 * @param queuePerMillionRequests managed queues (SQS standard), per million requests
	 * @param loadBalancerHour an application load balancer, per hour
	 * @param loadBalancerLcuHour an application load balancer, per capacity unit-hour
	 * @param networkLoadBalancerHour a network load balancer, per hour
	 * @param networkLoadBalancerLcuHour a network load balancer, per capacity unit-hour
	 * @param dynamodbReadPerMillion DynamoDB on-demand, per million read request units
	 * @param dynamodbWritePerMillion DynamoDB on-demand, per million write request units
	 * @param dynamodbRcuHour DynamoDB provisioned, per read capacity unit-hour
	 * @param dynamodbWcuHour DynamoDB provisioned, per write capacity unit-hour
	 * @param dynamodbStorageGibMonth DynamoDB table storage, per GiB-month
	 * @param apiGatewayPerMillion an API gateway, per million requests
	 * @param rdsStorageGibMonth managed database storage (RDS gp3), per GiB-month
	 * @param kubernetesControlPlaneHour a managed Kubernetes cluster's control plane
	 * (EKS), per hour
	 */
	@ModelName("RatesModel")
	record Rates(@Ge(0) @Nullable Double storageGibMonth, @Ge(0) @Nullable Double serverlessGbSecond,
			@Ge(0) @Nullable Double serverlessPerMillionRequests, @Ge(0) @Nullable Double queuePerMillionRequests,
			@Ge(0) @Nullable Double loadBalancerHour, @Ge(0) @Nullable Double loadBalancerLcuHour,
			@Ge(0) @Nullable Double networkLoadBalancerHour, @Ge(0) @Nullable Double networkLoadBalancerLcuHour,
			@Ge(0) @Nullable Double dynamodbReadPerMillion, @Ge(0) @Nullable Double dynamodbWritePerMillion,
			@Ge(0) @Nullable Double dynamodbRcuHour, @Ge(0) @Nullable Double dynamodbWcuHour,
			@Ge(0) @Nullable Double dynamodbStorageGibMonth, @Ge(0) @Nullable Double apiGatewayPerMillion,
			@Ge(0) @Nullable Double rdsStorageGibMonth, @Ge(0) @Nullable Double kubernetesControlPlaneHour) {
	}
}
