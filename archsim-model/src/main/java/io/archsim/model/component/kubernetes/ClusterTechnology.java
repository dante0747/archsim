package io.archsim.model.component.kubernetes;

import io.archsim.model.codec.JsonEnum;

/**
 * The Kubernetes distribution or managed service; they behave alike here and differ in
 * their presets.
 */
public enum ClusterTechnology implements JsonEnum {

	KUBERNETES("kubernetes"), EKS("eks"), GKE("gke"), AKS("aks"), K3S("k3s"), OPENSHIFT("openshift");

	private final String json;

	ClusterTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
