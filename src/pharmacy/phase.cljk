(ns pharmacy.phase
  "Phase 0→3 staged rollout — this actor's analog of robotaxi's ODD phases
  and `cloud-itonami-isic-6311`/`cloud-itonami-isic-7820`'s rollout
  phases: start narrow (read-only), widen as trust grows. Where the
  PharmacyGovernor answers 'is this allowed?', the phase answers 'how much
  autonomy does the actor have *yet*?'. It can only ever make the actor
  MORE conservative than the governor: it downgrades a governor-clean
  commit to approval or hold, never the reverse.

    Phase 0  read-only         — no writes at all. `:report/query` only
                                 (still governor-gated).
    Phase 1  assisted-otc      — `:otc/dispense` allowed, every dispense
                                 needs human approval.
    Phase 2  + rx/dispute      — adds `:rx/dispense`, `:rx/refill` and
                                 `:dispute/request` (still approval-only).
    Phase 3  supervised auto   — governor-clean, high-confidence
                                 `:otc/dispense` may auto-commit.
                                 `:rx/dispense`/`:rx/refill` remain
                                 approval-only EVEN HERE — dispensing a
                                 controlled or prescription-only item
                                 legally requires a licensed pharmacist's
                                 final sign-off no matter how clean the
                                 automated checks are; auto-commit is
                                 structurally reserved for the
                                 unrestricted-and-non-interacting OTC
                                 case only.

  `:dispute/request` is deliberately NEVER a member of any phase's `:auto`
  set, at any phase — a dispensing dispute/adverse-event report always
  reaches a pharmacist, independent of the PharmacyGovernor's own
  always-escalate check on the same op.

  `gate` runs AFTER `policy/check`, taking the governor disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted disposition
  plus a reason when the phase changed it.")

(def read-ops  #{:report/query})
(def write-ops #{:otc/dispense :rx/dispense :rx/refill :dispute/request})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}. `:rx/dispense`/`:rx/refill`/
  `:dispute/request` are intentionally absent from every phase's `:auto`
  set."
  {0 {:label "read-only"      :writes #{}
                               :auto #{}}
   1 {:label "assisted-otc"   :writes #{:otc/dispense}
                               :auto #{}}
   2 {:label "assisted-rx"    :writes #{:otc/dispense :rx/dispense :rx/refill :dispute/request}
                               :auto #{}}
   3 {:label "supervised-auto" :writes #{:otc/dispense :rx/dispense :rx/refill :dispute/request}
                                :auto #{:otc/dispense}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (pharmacy.operation: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase number (`(get
  phases phase (get phases default-phase))`). This is directly reachable
  by any ordinary caller that simply omits :phase — not just malformed/
  malicious input — so it must be the MOST CONSERVATIVE phase, never the
  most permissive (this session already found and fixed the same
  fail-open shape in the `cloud-itonami-isic-6311`/`cloud-itonami-isic-
  7820` sibling templates: a caller who forgets :phase must never
  silently get maximum autonomy). `:rx/dispense`/`:rx/refill`/
  `:dispute/request` are unaffected either way — never in any phase's
  `:auto` set."
  1)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads (`:report/query`) pass through unchanged (phase restricts write
    autonomy, not governed reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase → HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible → ESCALATE (:phase-approval),
    even if the governor was clean."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
