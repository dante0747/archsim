---
status: accepted
date: 2026-11-17
decision-makers: Majid Abarghooei (owner)
---

# 0081. Price a third-party API without a price at its preset's, or leave it out of the month and say so

## Context and Problem Statement

A third-party API (`external_api`) is billed per call: its `costPerMillionRequests` times the calls
it gets at the base load. Through schema 1.8 that price defaulted to 0, and a saved design writes
its defaults out, so a price nobody set reads exactly like a price a person set to 0. The cost then
labelled it a price "set" on the part, and the web app said "your price". The site critic's review
of the live demo (16 Nov 2026) found the Kafka template's E-mail API billing 144.92 million calls a
month at $0.00 under "your price", while the catalog's email preset prices them at $1,000 a million.
What should an API whose price nobody set cost, and how does the cost say where each price came
from?

## Decision Drivers

- A number on the cost says where it came from; a figure nobody chose is never shown as chosen.
- A free API ($0 a call) stays expressible.
- The monthly total does not quietly leave a part out.
- Designs saved before the change read as their authors meant them, as far as the saved JSON can
  tell.
- The same rule for an API's price as for a part's instance-hour price, where a technology preset's
  illustrative price stands in for a price nobody set.

## Considered Options

- (a) Let the price be unset (schema 1.9): unset takes the technology preset's price, else the API
  is not priced and the month says it leaves it out; the upgrade to 1.9 reads a saved 0 as unset
- (b) Keep the default of 0 and treat a price equal to it as not set, as the instance-hour price
  treats its default
- (c) Keep the default of 0 and only relabel it "default price"

## Decision Outcome

Chosen option: (a).

- **Schema 1.9.** `costPerMillionRequests` may be left empty; empty means "not set". 0 is a price:
  a free API.
- **Where the price comes from**, by the rule of a part's instance-hour price (`Quotes`): a price
  a person set on the part (`set`: written, and either no preset of its technology has that
  price or its provenance is measured or assumed); else a technology preset's illustrative price
  (`preset`: written on the part, as the library's presets write it, or, left empty, the first
  preset of its technology that has one), its note naming the preset, such as "E-mail API is
  priced at the Email API preset's illustrative $1000.00 per million calls: set your provider's
  price on the part"; else none. The payments and identity-provider presets set no price per call
  any more: card fees are a share of each order, and identity providers price by active user.
- **An API with no price** has no unit price, costs nothing in the month, and is listed in the
  cost's new `unpriced` (report 1.17), with the note "Not priced: Payments API (no price per
  million calls set, and no preset price for its technology): the month leaves it out." The app
  marks the total ("leaves out Payments API (no price)") wherever it shows it.
- **Labels.** The app says "set on the part" ("A price set on this part in the design"), never
  "your price"; "preset price", naming the technology; "not priced". The prices line says
  "prices set on the parts" where it said "your prices". The notification template's e-mail and
  SMS providers and the CDN's segment storage carry their presets' prices, so they now say
  "preset price": those figures are illustrative.
- **The upgrade to 1.9** removes an API's price of 0 written before 1.9, the old default a saved
  design wrote out, unless the design's provenance says a person stated it (measured or
  assumed). The app reads every design it opens so, a saved project and an imported file
  alike.
- **Templates set their prices.** The Kafka template's E-mail API costs Amazon SES's list price,
  $0.10 per 1,000 e-mails ($100 a million). The payments, carrier-label and fraud-scoring APIs
  set $0 and say why in their descriptions (a cost of sales, not of the architecture), the
  identity provider $0 (priced by monthly active users) and the push gateway $0 (FCM and APNs are
  free).
- **Engine 1.2.0**, because a cost changes for the same design and seed.

### Consequences

- Good, because a price on the cost is either the design's, a named preset's, or absent, and
  the month says what it leaves out.
- Good, because a free API is a price of 0 the design states, and reads as such.
- Bad, because a person who deliberately typed 0 before schema 1.9 sees the API priced at its
  preset, or not priced, until they type 0 again: a saved 0 could not be told from the default.
  The CHANGELOG says so.
- Bad, because the parity tests against the frozen Python engine, which defaulted the price to 0
  and called every written price set, carry one more structured departure: where its output
  records the price as 0, the Java's is read as it once the Java's side is shown to be an unset
  or unstated price (`ApiPriceDepartures`); and an API line it priced at 0 that the Java prices
  at its technology preset's price or not at all, or one whose written price is a preset's, is
  put back after its rate and calls are checked (`ApiCostDepartures`). The departure is keyed on
  the Java's line, not on the design, so a test of its own keeps a price of 0 set on a part from
  being lost on the way through a run (`ApiPriceTests`).
- Neutral: the Kafka template's month goes from $1,610 to about $16,100, the e-mails' $14,492
  included, which is what sending 55 e-mails a second all month costs at SES's list price.

### Confirmation

`ApiPriceTests` (the price's three sources, a preset's price typed by a person, the notes,
`unpriced`, a design with no price at all, and a 0 kept through a run), `UpgradesTests` (the step
to 1.9 removes only an API's 0 nobody stated), `ComponentCatalogTests` (the field may be empty),
the web's `cost.test.ts`, `document.test.ts` and `editor.test.ts`, and `SizingAuditTests`' Kafka
reference, worked out by hand.

## Pros and Cons of the Options

### (b) A price equal to the default counts as not set

- Good, because the schema does not change, and the parity departures are fewer.
- Bad, because a free API cannot be stated: the field shows 0 while the cost bills a preset's
  price, the very contradiction the review found, the other way round.

### (c) Relabel only

- Good, because nothing else changes.
- Bad, because 144.92 million e-mails still cost $0.00, now with a label that admits it.

## More Information

The instance-hour rule this follows: `Quotes` in archsim-engine's `cost` package (a price equal to
the catalog default counts as not set, and a preset's price stands in for it).
[docs/simulation-model.md](../simulation-model.md#cost-assumptions) describes the cost.
