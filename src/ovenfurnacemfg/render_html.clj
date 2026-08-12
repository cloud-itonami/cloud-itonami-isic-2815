(ns ovenfurnacemfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo previously had NO demo
  page and no generator at all. This namespace drives the REAL actor
  stack (`ovenfurnacemfg.operation` -> `ovenfurnacemfg.governor` ->
  `ovenfurnacemfg.store`) through `langgraph.graph/run*`, using the
  scenario this repo's OWN demo driver (`ovenfurnacemfg.sim`,
  `clojure -M:dev:run`) already exercises -- confirmed BEFORE writing
  this file to produce a real ledger of 4 commits and 11 HARD governor
  holds against the real seeded ids (`batch-001`..`batch-003`,
  `fab-001`, `bench-002`).

  NOTHING on the rendered page is hand-typed domain content. Every
  batch, equipment unit, quantity, temperature, defect rate,
  maintenance/shipment draft number, violation rule and violation
  detail string is read back out of `ovenfurnacemfg.store` or off the
  governor's own audit facts after the run. Even the phase/gate tables
  are derived from `ovenfurnacemfg.phase/phases` and
  `ovenfurnacemfg.governor/allowed-ops` rather than described by hand,
  so they cannot drift from the code they document.

  The page is deterministic: no timestamps, no random, no reliance on
  hash-map iteration order (every set is sorted before rendering, every
  store listing is already `sort-by :id`, the ledger is an append-only
  vector). Two consecutive runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [ovenfurnacemfg.store :as store]
            [ovenfurnacemfg.governor :as governor]
            [ovenfurnacemfg.phase :as phase]
            [ovenfurnacemfg.operation :as op]
            [langgraph.graph :as g]))

;; The coordinator identity this repo's own `ovenfurnacemfg.sim` uses.
(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

;; ----------------------------- the real run -----------------------------

(defn run-demo!
  "Runs a freshly seeded store through the scenario `ovenfurnacemfg.sim`
  already drives, so every disposition below is this actor's real
  behaviour rather than a story about it.

  Clean path (4 commits): a production-batch intake on `batch-001`
  (phase-3 auto-commit -- the only op in phase 3's `:auto` set); a
  maintenance window `mnt-1` on the verified+registered fabrication
  line `fab-001` (escalates -- `:schedule-maintenance` is deliberately
  absent from every phase's `:auto` set -- then approved); a safety
  concern `concern-1` (ALWAYS escalates on
  `:coordination/safety-concern`, approved); an outbound shipment
  `ship-1` of 50 units against `batch-001` (escalates, approved --
  100 already shipped + 50 is inside the batch's own recorded 400).

  HARD holds (11, none of which can reach a human): a caller whose own
  request `:effect` is not `:propose`; an unrecognized op
  (`:actuate-fabrication-line`, which also trips the proposal-effect
  allowlist); maintenance against the UNVERIFIED/unregistered thermal
  test bench `bench-002`; a shipment against the UNVERIFIED/
  unregistered batch `batch-003`; a shipment whose 10 units would push
  `batch-002` past its own recorded quantity (115 already shipped of
  120); a maintenance proposal that tries to ACTUATE equipment
  (permanent, no override); a double-schedule of `mnt-1`; and four
  batch-record patches carrying a fabricated product type, an
  implausible thermal-test reading, an implausible defect rate, and an
  attempt to self-issue a UL/CSA/CE combustion-equipment safety
  certification (permanent, no override).

  Returns {:db store :approvals [..]} -- `:approvals` is lifted
  verbatim off the real `:audit` channel of the resumed runs, so the
  approval trail on the page is actor output too."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        trail (volatile! [])
        exec! (fn [tid request]
                (g/run* actor {:request request :context coordinator}
                        {:thread-id tid}))
        approve! (fn [tid]
                   (let [r (g/run* actor {:approval {:status :approved :by "coord-1"}}
                                   {:thread-id tid :resume? true})
                         audit (get-in r [:state :audit])
                         asked (first (filter #(= :approval-requested (:t %)) audit))
                         granted (first (filter #(= :approval-granted (:t %)) audit))]
                     (vswap! trail conj
                             {:thread tid
                              :op (:op asked)
                              :subject (:subject asked)
                              :reason (:reason asked)
                              :phase (:phase asked)
                              :confidence (:confidence asked)
                              :by (:by granted)})
                     r))]

    ;; --- clean path ---
    (exec! "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                 :patch {:product-type :industrial-oven :last-assessed "2026-07-14"}})

    (exec! "t2" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                 :value {:equipment-id "fab-001" :maintenance-type :burner-nozzle-inspection
                         :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! "t2")

    (exec! "t3" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                 :value {:equipment-id "fab-001" :severity :moderate
                         :description "バーナー燃焼異常兆候、不完全燃焼の疑い"}})
    (approve! "t3")

    (exec! "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                 :value {:batch-id "batch-001" :units 50.0
                         :destination "buyer-yard-north"}})
    (approve! "t4")

    ;; --- HARD holds ---
    (exec! "t5" {:op :log-production-batch :effect :direct-write :subject "batch-001"
                 :patch {:product-type :industrial-oven}})

    (exec! "t6" {:op :actuate-fabrication-line :effect :propose :subject "batch-001"})

    (exec! "t7" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                 :value {:equipment-id "bench-002" :maintenance-type :calibration
                         :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! "t8" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                 :value {:batch-id "batch-003" :units 50.0
                         :destination "buyer-yard-south"}})

    (exec! "t9" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                 :value {:batch-id "batch-002" :units 10.0
                         :destination "buyer-yard-east"}})

    (exec! "t10" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                  :value {:equipment-id "fab-001" :maintenance-type :force-run
                          :scheduled-date "2026-09-01" :actuate-equipment? true}})

    (exec! "t11" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                  :value {:equipment-id "fab-001" :maintenance-type :burner-nozzle-inspection
                          :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! "t12" {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:product-type :unobtainium}})

    (exec! "t13" {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:thermal-test-degc 999999.0}})

    (exec! "t14" {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:defect-rate-percent 999.0}})

    (exec! "t15" {:op :log-production-batch :effect :propose :subject "batch-001"
                  :patch {:issue-certification? true}})

    {:db db :approvals @trail}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- token
  "Render an EDN token the way the actor itself carries it -- keywords
  keep their leading colon and namespace so `:batch/upsert` and
  `:shipment/propose` stay distinguishable on the page."
  [x]
  (if (nil? x) "—" (esc (pr-str x))))

(defn- plain
  "A raw store value (string/number) rendered as-is, or an em dash when
  the store genuinely holds nil."
  [x]
  (if (nil? x) "—" (esc x)))

(defn- flag [b yes no]
  (if b
    (str "<span class=\"ok\">" yes "</span>")
    (str "<span class=\"critical\">" no "</span>")))

(defn- token-list [xs]
  (if (seq xs) (str/join ", " (map token xs)) "—"))

(defn- sorted-ops
  "Sets have no reading order; sort by the printed token so the page is
  byte-stable."
  [s]
  (sort-by str s))

;; ----------------------------- tables -----------------------------

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell
  "The disposition of the LAST ledger fact naming `subject`. Only
  `:committed` and `:governor-hold` ever reach this store's ledger --
  an approval commits as `:committed` (the `:approval-granted` fact
  stays on the run's audit channel, see the approval trail below)."
  [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no direct activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold · "
           (token (-> f :violations first :rule)) "</span>")
      (= :approval-rejected (:t f)) "<span class=\"warn\">approval rejected</span>"
      :else (str "<span class=\"muted\">" (token (:t f)) "</span>"))))

(defn- batch-row [ledger {:keys [id product-type model thermal-test-degc quantity-units
                                 shipped-units defect-rate-percent verified? registered?
                                 last-assessed]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td>"
               "<td class=\"num\">%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (plain id) (token product-type) (plain model)
          (plain thermal-test-degc) (plain quantity-units) (plain shipped-units)
          (plain defect-rate-percent)
          (flag verified? "verified" "UNVERIFIED")
          (flag registered? "registered" "unregistered")
          (str (plain last-assessed) " / " (status-cell ledger id))))

(defn- equipment-row [{:keys [id kind verified? registered?
                              last-maintenance-date last-scheduled-maintenance-date]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td></tr>")
          (plain id) (token kind)
          (flag verified? "verified" "UNVERIFIED")
          (flag registered? "registered" "unregistered")
          (plain last-maintenance-date)
          (plain last-scheduled-maintenance-date)))

(defn- phase-row [[n {:keys [label writes auto]}]]
  (format "        <tr><td class=\"num\">%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          n (esc label)
          (token-list (sorted-ops writes))
          (if (seq auto) (token-list (sorted-ops auto))
              "<span class=\"muted\">none — every write needs a human</span>")))

(defn- gate-row
  "One row per op on the governor's closed allowlist, with its phase-3
  posture read straight out of `ovenfurnacemfg.phase/phases`."
  [op]
  (let [{:keys [writes auto]} (get phase/phases phase/default-phase)]
    (format "        <tr><td><code>%s</code></td><td>%s</td></tr>"
            (token op)
            (cond
              (not (contains? writes op))
              "<span class=\"critical\">not writable at this phase — HOLD (:phase-disabled)</span>"
              (contains? auto op)
              "<span class=\"ok\">auto-commit when the governor is clean</span>"
              :else
              "<span class=\"warn\">always escalates to a human — never in any phase's :auto set</span>"))))

(defn- approval-row [{:keys [thread op subject reason phase confidence by]}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td>"
               "<td>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td>%s</td></tr>")
          (plain thread) (token op) (plain subject)
          (token reason) (plain phase) (plain confidence) (plain by)))

(defn- maintenance-row [{:keys [id equipment-id maintenance-type scheduled-date
                                actuate-equipment? scheduled? maintenance-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td><code>%s</code></td></tr>")
          (plain id) (plain equipment-id) (token maintenance-type)
          (plain scheduled-date)
          (flag (not actuate-equipment?) "draft only" "ACTUATE")
          (flag scheduled? "scheduled" "not scheduled")
          (plain maintenance-number)))

(defn- draft-record-row [r]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td>"
               "<td><code>%s</code></td><td>%s</td></tr>")
          (plain (get r "record_id")) (plain (get r "kind"))
          (plain (or (get r "maintenance_id") (get r "shipment_id")))
          (plain (or (get r "equipment_id") "—"))
          (flag (get r "immutable") "immutable" "mutable")))

(defn- concern-row [{:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (plain id) (plain equipment-id) (token severity) (plain description)))

(defn- ledger-row [{:keys [t op subject disposition basis confidence]}]
  (format (str "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td>"
               "<td>%s</td><td>%s</td><td class=\"num\">%s</td></tr>")
          (if (= :governor-hold t)
            (str "<span class=\"critical\">" (token t) "</span>")
            (str "<span class=\"ok\">" (token t) "</span>"))
          (token op) (plain subject) (token disposition)
          (token-list basis) (plain confidence)))

(defn- violation-row [{:keys [subject op violations]}]
  (str/join "\n"
            (for [{:keys [rule detail]} violations]
              (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
                           "<td><code>%s</code></td><td>%s</td></tr>")
                      (plain subject) (token op) (token rule) (plain detail)))))

;; ----------------------------- the page -----------------------------

(defn- table [caption note headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" caption "</h2>\n"
       "    <p class=\"muted\">" note "</p>\n"
       "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       body-rows "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders the operator console from a store `db` that has already run
  `run-demo!` (or any other real scenario) plus that run's real
  approval trail."
  [db approvals]
  (let [ledger (vec (store/ledger db))
        holds (filter #(= :governor-hold (:t %)) ledger)
        commits (filter #(= :committed (:t %)) ledger)]
    (str
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2815 · ovenfurnacemfg — Operator Console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Ovens, furnaces &amp; furnace burners (ISIC 2815) — Plant Operations Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance scheduling and equipment actuation are never automatic</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">Generated at build time by <code>ovenfurnacemfg.render-html</code> (<code>clojure -M:dev:render-html</code>), by driving <code>ovenfurnacemfg.operation</code> → <code>ovenfurnacemfg.governor</code> → <code>ovenfurnacemfg.store</code> through <code>langgraph.graph/run*</code>. Every value below was read back out of the store or off the governor's own audit facts after the run — nothing on this page is hand-written domain content.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Ledger facts</th><th>Committed</th><th>HARD governor holds</th><th>Human approvals granted</th></tr></thead>\n"
     "      <tbody>\n"
     (format (str "        <tr><td class=\"num\">%s</td><td class=\"num\">%s</td>"
                  "<td class=\"num\">%s</td><td class=\"num\">%s</td></tr>")
             (count ledger) (count commits) (count holds) (count approvals)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     (table "Production batches"
            "Post-run state of <code>ovenfurnacemfg.store</code>. <code>shipped-units</code> on <code>batch-001</code> moved because a shipment actually committed; the two batches that were only ever <em>referenced</em> by a held proposal are untouched, which is what a hold is supposed to mean."
            ["Batch" "Product type" "Model" "Thermal test (°C)" "Quantity (units)"
             "Shipped (units)" "Defect rate (%)" "QC" "Registry" "Last assessed / last op"]
            (str/join "\n" (map (partial batch-row ledger) (store/all-batches db))))

     (table "Plant equipment"
            "Equipment ground truth. <code>:verified?</code> and <code>:registered?</code> are re-derived independently by the governor on every maintenance proposal — the advisor's own rationale is never trusted for them."
            ["Equipment" "Kind" "QC" "Registry" "Last maintenance" "Last scheduled maintenance"]
            (str/join "\n" (map equipment-row (store/all-equipment db))))

     (table "Rollout phases"
            (str "Read directly from <code>ovenfurnacemfg.phase/phases</code>, so this table cannot drift from the gate that actually runs. This console ran at phase "
                 phase/default-phase " (<code>"
                 (esc (:label (get phase/phases phase/default-phase)))
                 "</code>).")
            ["Phase" "Label" "Writable ops" "Auto-commit ops"]
            (str/join "\n" (map phase-row (sort-by key phase/phases))))

     (table "Action gate (Oven &amp; Furnace Plant Operations Governor)"
            "One row per op on <code>ovenfurnacemfg.governor/allowed-ops</code>, with its posture at the phase this console ran. Anything outside this closed allowlist is a HARD hold. Two further boundaries are permanent and unconditional at every phase — directly actuating fabrication/assembly/thermal-test equipment, and self-issuing a UL/CSA/CE combustion-equipment safety certification — and no human approval can override either."
            ["Op" "Gate at this phase"]
            (str/join "\n" (map gate-row (sorted-ops governor/allowed-ops))))

     (table "Human approval trail"
            "Lifted off the real <code>:audit</code> channel of the resumed actor runs. <code>interrupt-before #{:request-approval}</code> paused each of these mid-graph and handed the decision to a human; the approval then commits as a <code>:committed</code> ledger fact."
            ["Thread" "Op" "Subject" "Escalation reason" "Phase" "Confidence" "Approved by"]
            (str/join "\n" (map approval-row approvals)))

     (table "HARD holds — what the governor refused"
            "Every row is a <code>:governor-hold</code> fact off the ledger, with the violation rule and the governor's own detail string. None of these reached a human: a HARD violation is not escalated, it is refused."
            ["Subject" "Op" "Rule" "Detail (verbatim from the governor)"]
            (str/join "\n" (map violation-row holds)))

     (table "Committed maintenance windows"
            "Only maintenance the governor cleared AND a human approved is here. The two held proposals (<code>mnt-2</code> against unverified equipment, <code>mnt-3</code> attempting actuation) produced no store record at all."
            ["Maintenance" "Equipment" "Type" "Scheduled date" "Actuation" "State" "Draft number"]
            (str/join "\n" (map maintenance-row (store/all-maintenance db))))

     (table "Draft registry records"
            "Built by <code>ovenfurnacemfg.registry</code>. These are DRAFT records a plant coordinator would keep — this actor never actuates equipment and never dispatches a real freight carrier, and every certificate it emits is unsigned."
            ["Record" "Kind" "Subject" "Equipment" "Immutability"]
            (str/join "\n" (map draft-record-row
                                (concat (store/maintenance-history db)
                                        (store/shipment-history db)))))

     (table "Safety concerns"
            "A safety concern always carries <code>:coordination/safety-concern</code> stake, so it always escalates to a human regardless of confidence, and it is never blocked on the referenced equipment being verified — a concern may be raised about any equipment."
            ["Concern" "Equipment" "Severity" "Description"]
            (str/join "\n" (map concern-row (store/safety-concerns db))))

     (table "Audit ledger (this run)"
            "The append-only decision-fact log, in order. This is the whole audit trail: what was proposed, what committed, and what the governor refused."
            ["Fact" "Op" "Subject" "Disposition" "Basis" "Confidence"]
            (str/join "\n" (map ledger-row ledger)))

     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db approvals]} (run-demo!)
        hs (filter #(= :governor-hold (:t %)) (store/ledger db))]
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (let [html (render db approvals)
          f (java.io.File. ^String out)]
      (when-let [parent (.getParentFile f)] (.mkdirs parent))
      (spit f html :encoding "UTF-8")
      (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
               (count hs) "HARD governor holds,"
               (count approvals) "human approvals,"
               (count (store/maintenance-history db)) "maintenance drafts,"
               (count (store/shipment-history db)) "shipment drafts )"))))
