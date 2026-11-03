/**
 * The component catalog the editor renders its palette and inspector from
 * ({@code GET /components}): each component type's ports, settings, defaults, presets and
 * technologies, and the link and workload settings. {@link ComponentCatalog#of} builds it
 * from a registry.
 *
 * <p>
 * The facts come from the component specifications and their config records (types,
 * bounds, defaults, the {@code @Description} and {@code @Ui} of each setting, the
 * technologies' defaults); what the editor shows about a type beyond them (its palette
 * text, icon, presets, technology traits and their documentation) is data, read from
 * {@code io/archsim/model/catalog/<type>.json}.
 */
@NullMarked
package io.archsim.model.catalog;

import org.jspecify.annotations.NullMarked;
