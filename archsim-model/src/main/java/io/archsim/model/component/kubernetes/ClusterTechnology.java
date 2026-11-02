package io.archsim.model.component.kubernetes;

import io.archsim.model.codec.JsonEnum;

/**
 * The Kubernetes distribution or managed service; they behave alike here and differ in
 * their presets.
 */
public enum ClusterTechnology implements JsonEnum {

	/** Upstream Kubernetes. */
	KUBERNETES("kubernetes"),
	/** Amazon Elastic Kubernetes Service. */
	EKS("eks"),
	/** Google Kubernetes Engine. */
	GKE("gke"),
	/** Azure Kubernetes Service. */
	AKS("aks"),
	/** K3s, a lightweight Kubernetes distribution. */
	K3S("k3s"),
	/** Red Hat OpenShift. */
	OPENSHIFT("openshift");

	private final String json;

	ClusterTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
