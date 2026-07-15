# ADR-0001: OvenFurnaceAdvisor ⊣ Oven & Furnace Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2815` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2815` publishes an OSS blueprint for manufacture of
ovens, furnaces and furnace burners -- industrial-scale heat-treatment/
melting furnaces and burners (distinct from household-appliance ovens)
-- **plant operations coordination** (production-batch product-type/
thermal-test/quantity/defect-rate data logging, fabrication/assembly/
thermal-test-bench-equipment maintenance scheduling, safety-concern
flagging, and outbound product shipment coordination). Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2812` (Manufacture of
fluid power equipment): both are back-office coordination actors for a
fixed manufacturing plant with QC-tested, discrete-unit finished-goods
output and a real physical/consumer safety dimension, and both share
the same four-op shape (`:log-production-batch`/`:schedule-
maintenance`/`:flag-safety-concern`/`:coordinate-shipment`), the same
two-entity verified/registered gate structure (equipment for
maintenance scheduling, batch for shipment coordination), and the same
permanent equipment-actuation and certification-authority blocks. This
build mirrors `cloud-itonami-isic-2812`'s architecture closely but
adapts the hazard profile, equipment vocabulary, and product taxonomy
to the oven/furnace/furnace-burner plant: its finished goods are
fabricated, assembled and thermal-tested industrial ovens, heat-
treatment furnaces, melting furnaces and furnace burners rather than
hydraulic/pneumatic fluid power equipment, so its equipment kinds are
`:fabrication-line` and `:thermal-test-bench` rather than 2812's
machining line and pressure-test bench, and its routine QC field is
`:thermal-test-degc` (industrial oven/furnace/burner thermal-
performance test, plausibility-checked 0-3000 degC -- informed by real
industrial-furnace test practice: heat-treatment furnaces commonly
operate in the 200-1300 degC range, melting furnaces such as steel
electric-arc or induction furnaces can reach roughly 1700 degC, and
specialized high-temperature furnaces (sintering, glass-melting) can
exceed 1600 degC) rather than 2812's `:pressure-test-bar` (hydraulic/
pneumatic proof-pressure test). Like 2812, shipment quantity is
tracked in finished-unit UNITS (`:units`/`:quantity-units`/`:shipped-
units`), since ovens/furnaces/furnace burners are likewise discrete
counted units rather than a bulk weight.

This vertical shares 2812's structural DOMAIN-SPECIFIC permanent
block, adapted to the combustion-equipment safety-certification
regime: industrial ovens, furnaces and furnace burners are subject to
combustion-equipment safety-certification regimes (e.g. UL 795
commercial-industrial gas-fired equipment / UL 726 oil-fired equipment
listing, CSA certification, CE marking under the EU Gas Appliances
Regulation 2016/426). This actor is never the certification authority
-- any proposal (regardless of op) that declares `:issue-
certification? true` is a HARD, PERMANENT, unconditional block
(`ovenfurnacemfg.governor/certification-authority-blocked-
violations`), the same "no phase, no human override" posture as the
equipment-actuation block.

This vertical has NO pre-existing `kotoba-lang/ovenfurnacemfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`ovenfurnacemfg.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, thermal-test plausibility
validation, defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2812`'s `fluidpowermfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:oven-furnace-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "oven-furnace-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external oven/furnace-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
oven/furnace vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-quantity /
product-type / thermal-test / defect-rate validation
functions live as pure functions in `ovenfurnacemfg.registry` and are
re-verified independently by `ovenfurnacemfg.governor` -- the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2812`'s `fluidpowermfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of oven/furnace
plant operations. It does NOT:
- Control fabrication or assembly-line equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate fabrication/assembly-line equipment
- Self-issue a UL/CSA/CE combustion-equipment safety-certification mark (e.g. UL 795 / UL 726 / CSA / CE under the EU Gas Appliances Regulation 2016/426)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: oven/furnace/furnace-burner manufacturing
is a safety-critical domain (fabrication/assembly/thermal-test-bench
line hazards, high-temperature and combustion/burner hazards,
combustion-equipment safety certification, downstream fire/burn/
consumer-safety consequence via the systems the batch's ovens/
furnaces/burners end up installed in). Safety-concern flagging NEVER
auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (equipment-safety concern, burner-combustion-
safety concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" -- it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2812`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date unit quantity plus the
proposal's own claimed unit quantity would exceed the batch's own
recorded production quantity -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `ovenfurnacemfg.governor`, mirroring `cloud-itonami-isic-2812`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct fabrication/assembly-line-equipment control, equipment actuation, or self-issued combustion-equipment safety certification (UL/CSA/CE) is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Oven/furnace/furnace-burner plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no combustion-equipment safety-certification
mark can ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2815`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-thermal-test-degc, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
