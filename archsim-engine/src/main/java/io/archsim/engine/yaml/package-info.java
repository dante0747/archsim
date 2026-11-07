/**
 * YAML from sources ArchSim does not control (imported files, calibration mappings), read
 * as the Python engine's PyYAML safe loader read it, within its bounds (ADR-0043). What
 * is wrong with a text is one of four exceptions, as Python told them apart: text that is
 * not valid YAML ({@link io.archsim.engine.yaml.InvalidYamlException}), a value Python
 * refuses ({@link io.archsim.engine.yaml.YamlValueException}), a scalar Python looks up
 * in vain ({@link io.archsim.engine.yaml.YamlKeyException}) and another scalar it fails
 * to read ({@link io.archsim.engine.yaml.YamlScalarException}); each caller words them as
 * its Python counterpart did.
 */
@NullMarked
package io.archsim.engine.yaml;

import org.jspecify.annotations.NullMarked;
