package io.archsim.cli;

import picocli.CommandLine.IVersionProvider;

import io.archsim.engine.version.ArchsimVersion;
import io.archsim.engine.version.EngineVersions;

/**
 * What {@code --version} prints: ArchSim's version, then the engine's, which moves only
 * when what a simulation means changes (ADR-0076).
 */
final class VersionProvider implements IVersionProvider {

	@Override
	public String[] getVersion() {
		return new String[] { "archsim " + ArchsimVersion.PRODUCT + ", engine " + EngineVersions.ENGINE };
	}

}
