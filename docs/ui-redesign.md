# UI critique and design principles

A blunt review of the interface as it stood on 2026-10-25 (the "night shift" look), and the rules
the redesign follows. Use the DOs and DON'Ts as a checklist for any new screen.

## The verdict

The product does real work, but the interface makes people work harder than the problem does.

1. **Boxes inside boxes.** Floating islands hold cards that hold tiles that hold pills. There are
   448 `border` uses in the UI code. Every element shouts "I am a container"; nothing is content.
2. **Typographic noise.** 20 different pixel font sizes, about 200 uses below 12 px, and monospace
   in 224 places. It reads like a terminal log: tiring, and hard to scan.
3. **The canvas, which is the product, gets the least space.** Two fixed side panels and gutters
   take about 40% of the width; the diagram opens at roughly half zoom, so part names render at
   9 px and nobody can read their own design without zooming.
4. **Crammed parts.** Slanted, hexagonal and cloud shapes squeeze text into odd corners; each part
   shows four to six rows of tiny data, stacked "replica" layers and a footer. Names truncate.
5. **Decoration over meaning.** Gradients, glows, an aurora, glass blur, drop shadows, gradient
   text and `// UPPERCASE` labels. It dates the product and competes with the data.
6. **Clutter floating over the work.** A result card sits on top of the diagram, the zoom toolbar
   collides with parts, plus a review chip, hints and an attribution, all overlapping content.
7. **Colour without a system.** Violet, cyan and pink accents, seven family colours and four
   status colours, all saturated, often side by side.
8. **Four levels of tabs.** Workspace tabs, section tabs, sub-tabs and side-panel tabs. People
   lose track of where they are.
9. **A parts library you have to decode.** Forty two-column tiles with truncated names
   ("API-gateway-style...") and no hierarchy.
10. **Flat-priority forms.** The inspector lists every setting with equal weight inside boxes;
    the two decisions that matter (size and capacity) do not stand out.
11. **Results look unfinished in places.** Monospace numbers, dashed "Add a target" ghost cards and
    a narrow centred column that wastes a wide screen.
12. **No delight.** Running a simulation is a wait with a spinner. Nothing moves to show traffic,
    and finishing a run has no moment of satisfaction.

## Direction: calm canvas, crisp parts, motion that explains

Flat, quiet surfaces; one typeface; colour only where it means something; the diagram is the hero;
motion is used to show traffic and state changes, never as decoration.

## DOs

- **Do give the canvas the room.** Dock panels edge to edge (no floating islands or gutters), let
  them collapse, and open diagrams at a readable zoom.
- **Do use one type family and a short scale.** Geist for everything; sizes 12 / 13 / 14 / 16 / 20,
  display 28 / 40. Numbers use tabular figures, not a monospace font.
- **Do separate with space and tone, not lines.** One hairline between regions; inside a region,
  whitespace and headings do the grouping.
- **Do keep surfaces flat.** Three tonal steps (canvas, surface, raised). Shadows only on things
  that truly float (menus, dialogs), and then one soft shadow.
- **Do keep one accent colour** for primary actions, selection and focus. Every other colour means
  something: status, or a part family's tint on its icon chip.
- **Do make parts uniform cards.** Rounded rectangle, brand logo on a tinted chip, name, one line
  of detail, a status dot. Everything else lives in the inspector or on hover.
- **Do show traffic as motion.** Links animate while a run is in progress and during replay.
- **Do put status where the eye already is.** One status line in the top bar, not a card over
  the diagram.
- **Do use an 8 px grid,** 8 to 12 px radii and 32 / 36 px control heights.
- **Do write short, sentence-case labels.**
- **Do keep it keyboard-first:** the command palette, and shortcuts shown in tooltips.
- **Do welcome people and celebrate outcomes:** friendly empty states, a clear "it holds up" moment.

## DON'Ts

- **Don't nest cards in cards** or border every element.
- **Don't go below 12 px** for text (11 px only for tiny badges on the canvas).
- **Don't use monospace for interface text;** keep it for code, YAML and IDs.
- **Don't use gradients, glows, glassmorphism, auroras or gradient text.**
- **Don't uppercase labels or prefix them with `//`.**
- **Don't float panels over content** they hide.
- **Don't show everything at once:** progressive disclosure beats completeness.
- **Don't put more than one saturated colour in a region.**
- **Don't truncate names in lists** or hide primary actions in menus.
- **Don't animate for decoration;** animate state changes and respect reduced motion.

## What changes

- Tokens: flat surfaces, softer lines, no glass or glow, minimal shadows, one accent per theme.
  (Later on 2026-10-25 the six themes became Blueprint and Drafting paper, and then, after the
  user picked it from four rendered directions, the "Ops console" look: graphite panels,
  signal-orange actions with a brand gradient and a glow on the primary button, blue
  selection, Grafana-style thresholds. The user found the calm, flat versions boring, so the
  "no gradients or glow" rule now has two deliberate exceptions in dark: the primary action
  and the brand. The light theme was then made standard and calm, GitHub-style, with a solid
  blue primary action and no glow, because the orange-on-white version tired the eyes.)
- Type: Geist only, the short scale above, tabular figures for numbers; monospace kept for code.
- Shell: docked top bar and side panels with hairlines, no gutters; the run summary moves into the
  top bar.
- Parts library: a one-column list with large logos, full names and a one-line description.
- Canvas: uniform part cards, a utilisation bar instead of a filling shape, quieter links with flow
  animation while running and replaying, toolbar out of the way.
- Inspector, Test and Results: fewer boxes, clearer headings, readable numbers.
- Home: a calm hero without effects, clearer template cards.

## Studio (2026-10-26)

The user found the Ops console look generic ("AI slop") and asked for something premium,
naming Apple's pro apps, Figma/Framer and Stripe/Vercel as references, and liking Paperdraw's
dark canvas, colourful icon tiles, game-like chaos and mono HUD. Three rendered directions were
turned down (a transit map, a neon reactor, a flat graphite one) before this one. The idea: an
architecture simulator is an instrument, so it borrows from a pro audio app.

- **Transport, not a toolbar.** Replay buttons and an LCD readout sit in the middle of the top
  bar; Simulate is the record button. The LCD shows the moment under the playhead during a
  replay and the run's summary otherwise.
- **Colour means family.** Apple's system colours, one per part family, as solid tiles with
  white logos, in the library, on cards and on the timeline lanes. Everything else stays
  charcoal and grey; red is kept for failures and missed objectives.
- **Meters, not bars.** Utilisation is a 12-segment level meter: green, then yellow, then red by
  position, so near-saturation reads at a glance.
- **Chaos as an instrument.** The quick failure tests are drum pads: hit one and it plans that
  failure and simulates; it stays lit while planned.
- **The replay as an arrangement.** One lane per part, a region in its family colour with
  its load drawn as a waveform and failures as red clips.
- **Figma precision.** Dot-grid canvas, corner handles on the selected part, segmented
  controls, 5 to 14 px radii.

Deliberate exceptions to the rules above: numbers in the LCD, on cards and in lanes use Geist
Mono (a readout, not interface text); the LCD's tiny cell labels are uppercase; the chaos rack
floats over the canvas, so it is narrow, foldable and folds itself on narrow canvases.
