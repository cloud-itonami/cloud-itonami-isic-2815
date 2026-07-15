# cloud-itonami-isic-2815: Manufacture of ovens, furnaces and furnace burners

Open Business Blueprint for **ISIC 2815**: manufacture of ovens, furnaces and furnace burners — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **industrial oven/furnace/furnace-burner plant operations**: production-batch data logging (product-type/thermal-test/quantity/defect-rate), fabrication/assembly/thermal-test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for oven/furnace/
furnace-burner-plant operations: run by a qualified operator so a
plant keeps its own operating records instead of renting a closed
SaaS.

## Scope: plant operations coordination, not fabrication/assembly-line control

ISIC 2815 covers the **manufacturing plant** that fabricates, assembles and thermal-tests finished industrial-scale ovens, heat-treatment furnaces, melting furnaces and furnace burners (industrial-scale heat-treatment/melting furnaces and burners — distinct from household-appliance ovens) — including thermal testing — before shipment. This actor coordinates the back-office record keeping around that plant — it never touches the fabrication/assembly-line equipment directly, and it is never a combustion-equipment safety-certification authority (e.g. UL 795 commercial-industrial gas-fired equipment / UL 726 oil-fired equipment listing, CSA certification, or CE marking under the EU Gas Appliances Regulation 2016/426).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — fabrication/assembly/thermal-test batch, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — fabrication/assembly/thermal-test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an equipment-safety/burner-combustion-safety concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(fabrication/assembly/thermal-test-bench line equipment, high-
temperature and combustion/burner hazards, combustion-equipment safety
certification, downstream fire/burn/consumer-safety consequence via
the systems the batch's ovens/furnaces/burners end up installed in):

- Does NOT control fabrication or assembly-line equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate fabrication/assembly-line equipment (human plant supervisor decides)
- Does NOT self-issue a UL/CSA/CE combustion-equipment safety-certification mark (e.g. UL 795 / UL 726 / CSA / CE under the EU Gas Appliances Regulation 2016/426 — the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`ovenfurnacemfg.operation/build`, a langgraph-clj StateGraph):
1. **`ovenfurnacemfg.advisor`** (sealed intelligence node, `OvenFurnaceAdvisor`): proposes decisions only, never commits
2. **`ovenfurnacemfg.governor`** (independent, `Oven & Furnace Plant Operations Governor`): validates against domain rules, re-derived from `ovenfurnacemfg.registry`'s pure functions and `ovenfurnacemfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct fabrication/assembly-line-equipment control)
     - Directly actuating fabrication/assembly-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a UL/CSA/CE combustion-equipment safety-certification mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:thermal-test-degc` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`ovenfurnacemfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`ovenfurnacemfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
