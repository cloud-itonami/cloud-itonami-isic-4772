(ns pharmacy.store
  "SSoT for the pharmacy actor, behind a `Store` protocol so the backend is
  a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/pharmacy/store_contract_test.clj) — the actor, the
  PharmacyGovernor and the audit ledger never know which SSoT they run on.

  Entity shapes (ADR-2607114772): a patient (age + allergy flags only — no
  broader medical record), an item (drug-catalog entry: OTC/Rx, DEA
  schedule, restricted-OTC limits), a prescription (patient × item ×
  prescriber × refill state), an erx-network (provenance for
  `:licensed-erx-network` dispensing), and a subscriber contract. There is
  NO field anywhere for order-routing/payment/shipping logistics — this
  actor only decides whether a dispense/refill is allowed and records that
  it happened, never fulfillment mechanics.

  The ledger stays append-only on every backend — 'who dispensed/refilled
  what, on what prescription/network, on what source basis' is always a
  query over an immutable log."
  (:require [clojure.string :as str]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (patient [s id])
  (item [s id])
  (prescription [s id])
  (erx-network [s network-id])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-patients [s patients]         "replace/seed patients (map id→patient)")
  (with-items [s items]               "replace/seed drug-catalog items (map id→item)")
  (with-prescriptions [s prescriptions] "replace/seed prescriptions (map id→prescription)")
  (with-erx-networks [s networks]     "replace/seed erx networks (map network-id→network)")
  (with-contracts [s contracts]       "replace/seed subscriber contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real patients) ─────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline and
  no real patient, prescriber or drug transaction is ever asserted by this
  repository. `pt-200` (age 16) and `pt-300` (penicillin allergy) carry demo
  flags purely to exercise the age-restriction and interaction-flag
  governor gates — they are not claims about any real person."
  []
  {:patients
   {"pt-100" {:id "pt-100" :name "山田 花子(デモ)" :age 34 :allergies #{}}
    "pt-200" {:id "pt-200" :name "未成年デモ患者" :age 16 :allergies #{}}
    "pt-300" {:id "pt-300" :name "アレルギーデモ患者" :age 40 :allergies #{:penicillin}}}
   :items
   {"item-otc-100" {:id "item-otc-100" :name "イブプロフェン(デモ)" :rx? false
                     :restricted? false :interacts-with #{}
                     :source {:class :fda-drug-registry :ref "ndc:demo-otc-100"}}
    "item-otc-200" {:id "item-otc-200" :name "疑似エフェドリン含有風邪薬(デモ)" :rx? false
                     :restricted? true :min-age 18 :max-quantity-grams 3.6M
                     :interacts-with #{}
                     :source {:class :fda-drug-registry :ref "ndc:demo-otc-200"}}
    "item-rx-100" {:id "item-rx-100" :name "アルプラゾラム(デモ)" :rx? true
                    :schedule :iv :max-quantity-per-fill 30 :interacts-with #{}
                    :source {:class :dea-schedule-reference :ref "dea-schedule:iv-demo"}}
    "item-rx-200" {:id "item-rx-200" :name "オキシコドン(デモ)" :rx? true
                    :schedule :ii :max-quantity-per-fill 15 :interacts-with #{}
                    :source {:class :dea-schedule-reference :ref "dea-schedule:ii-demo"}}
    "item-rx-300" {:id "item-rx-300" :name "ペニシリン系抗生剤(デモ)" :rx? true
                    :schedule nil :max-quantity-per-fill 20 :interacts-with #{:penicillin}
                    :source {:class :dea-schedule-reference :ref "dea-schedule:none-demo"}}}
   :prescriptions
   {"rx-100" {:id "rx-100" :patient-id "pt-100" :item-id "item-rx-100"
              :prescriber-npi "1234567890" :verified? true :expired? false
              :refills-remaining 2}
    "rx-200" {:id "rx-200" :patient-id "pt-100" :item-id "item-rx-200"
              :prescriber-npi "1234567890" :verified? true :expired? false
              ;; Schedule II: no refills permitted BY LAW, not "used up" —
              ;; the ceiling itself, exercised by restricted-quantity-gate.
              :refills-remaining 0}
    "rx-200b" {:id "rx-200b" :patient-id "pt-100" :item-id "item-rx-200"
               :prescriber-npi "1234567890" :verified? true :expired? false
               ;; Demo-only: simulates a data-entry error that let
               ;; refills-remaining go nonzero on a Schedule II record, to
               ;; prove restricted-quantity-gate's schedule-II-never-
               ;; refillable rule is a STRUCTURAL defense (fires
               ;; regardless of what this field says), not merely a
               ;; readback of :refills-remaining.
               :refills-remaining 1}
    "rx-expired" {:id "rx-expired" :patient-id "pt-100" :item-id "item-rx-100"
                  :prescriber-npi "1234567890" :verified? true :expired? true
                  :refills-remaining 1}
    "rx-300" {:id "rx-300" :patient-id "pt-300" :item-id "item-rx-300"
              :prescriber-npi "1234567890" :verified? true :expired? false
              :refills-remaining 1}}
   :erx-networks
   {"net-demo" {:network-id "net-demo" :provider "Demo eRx Network (fictitious)" :active? true}
    "net-expired" {:network-id "net-expired" :provider "Lapsed Demo eRx Network (fictitious)" :active? false}}
   :contracts
   {"tenant-chain" {:tenant "tenant-chain" :tier :tier/network :active? true :purpose :pharmacy-chain-portal}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true :purpose :single-store}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (patient [_ id] (get-in @a [:patients id]))
  (item [_ id] (get-in @a [:items id]))
  (prescription [_ id] (get-in @a [:prescriptions id]))
  (erx-network [_ network-id] (get-in @a [:erx-networks network-id]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :prescription-refill-apply (swap! a update-in [:prescriptions (first path) :refills-remaining] dec)
      :dispute-apply              (swap! a update-in [:prescriptions (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-patients [s ps]      (when (seq ps) (swap! a assoc :patients ps)) s)
  (with-items [s is]         (when (seq is) (swap! a assoc :items is)) s)
  (with-prescriptions [s rs] (when (seq rs) (swap! a assoc :prescriptions rs)) s)
  (with-erx-networks [s ns'] (when (seq ns') (swap! a assoc :erx-networks ns')) s)
  (with-contracts [s cts]    (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

;; Schema, the EDN-blob codec (enc/dec*), and the per-entity field-spec
;; map<->tx<->pull machinery are the shared kotoba-lang/langchain-store
;; substrate (ADR-2607141600) — the seam ~190 actors hand-roll. This store
;; keeps only the domain-specific field specs (patient/item/prescription/
;; erx-network/contract) and the ledger's seq-keyed event-log wiring.
(def ^:private schema
  (ls/identity-schema [:patient/id :item/id :prescription/id :erx-network/id
                       :contract/tenant :ledger/seq]))

(def ^:private patient-spec
  {:id {:attr :patient/id}
   :name {:attr :patient/name}
   :age {:attr :patient/age}
   :allergies {:attr :patient/allergies :blob? true :default #{}}})

(defn- patient->tx [m] (ls/map->tx patient-spec m))
(def ^:private patient-pull (ls/pull-pattern patient-spec))
(defn- pull->patient [m] (ls/pull->map patient-spec :id m))

(def ^:private item-spec
  {:id {:attr :item/id}
   :name {:attr :item/name}
   :rx? {:attr :item/rx :coerce boolean}
   :restricted? {:attr :item/restricted :coerce boolean}
   :min-age {:attr :item/min-age}
   :max-quantity-grams {:attr :item/max-quantity-grams :blob? true}
   :schedule {:attr :item/schedule}
   :max-quantity-per-fill {:attr :item/max-quantity-per-fill}
   :interacts-with {:attr :item/interacts-with :blob? true :default #{}}
   :source {:attr :item/source :blob? true}})

(defn- item->tx [m] (ls/map->tx item-spec m))
(def ^:private item-pull (ls/pull-pattern item-spec))
(defn- pull->item [m] (ls/pull->map item-spec :id m))

(def ^:private prescription-spec
  {:id {:attr :prescription/id}
   :patient-id {:attr :prescription/patient-id}
   :item-id {:attr :prescription/item-id}
   :prescriber-npi {:attr :prescription/prescriber-npi}
   :verified? {:attr :prescription/verified :coerce boolean}
   :expired? {:attr :prescription/expired :coerce boolean}
   :refills-remaining {:attr :prescription/refills-remaining}})

(defn- prescription->tx [m] (ls/map->tx prescription-spec m))
(def ^:private prescription-pull (ls/pull-pattern prescription-spec))
(defn- pull->prescription [m] (ls/pull->map prescription-spec :id m))

(def ^:private erx-network-spec
  {:network-id {:attr :erx-network/id}
   :provider {:attr :erx-network/provider}
   :active? {:attr :erx-network/active :coerce boolean}})

(defn- erx-network->tx [m] (ls/map->tx erx-network-spec m))
(def ^:private erx-network-pull (ls/pull-pattern erx-network-spec))
(defn- pull->erx-network [m] (ls/pull->map erx-network-spec :network-id m))

(def ^:private contract-spec
  {:tenant {:attr :contract/tenant}
   :tier {:attr :contract/tier}
   :active? {:attr :contract/active :coerce boolean}
   :purpose {:attr :contract/purpose}})

(defn- contract->tx [m] (ls/map->tx contract-spec m))
(def ^:private contract-pull (ls/pull-pattern contract-spec))
(defn- pull->contract [m] (ls/pull->map contract-spec :tenant m))

(defrecord DatomicStore [conn]
  Store
  (patient [_ id] (pull->patient (d/pull (d/db conn) patient-pull [:patient/id id])))
  (item [_ id] (pull->item (d/pull (d/db conn) item-pull [:item/id id])))
  (prescription [_ id] (pull->prescription (d/pull (d/db conn) prescription-pull [:prescription/id id])))
  (erx-network [_ network-id] (pull->erx-network (d/pull (d/db conn) erx-network-pull [:erx-network/id network-id])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :prescription-refill-apply
      (let [rx (prescription s (first path))]
        (d/transact! conn [(prescription->tx (update rx :refills-remaining dec))]))
      :dispute-apply
      (d/transact! conn [(prescription->tx (merge (prescription s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-patients [s ps]      (when (seq ps) (d/transact! conn (mapv patient->tx (vals ps)))) s)
  (with-items [s is]         (when (seq is) (d/transact! conn (mapv item->tx (vals is)))) s)
  (with-prescriptions [s rs] (when (seq rs) (d/transact! conn (mapv prescription->tx (vals rs)))) s)
  (with-erx-networks [s ns'] (when (seq ns') (d/transact! conn (mapv erx-network->tx (vals ns')))) s)
  (with-contracts [s cts]    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  ([] (datomic-store {}))
  ([{:keys [patients items prescriptions erx-networks contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-patients patients) (with-items items) (with-prescriptions prescriptions)
         (with-erx-networks erx-networks) (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
