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

  Entity shapes (ADR-2607113000): a patient (age + allergy flags only — no
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
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

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
  "A small, entirely fictitious dataset so the actor + tests run offline
  and no real patient, prescriber or drug transaction is ever asserted by
  this repository. `pt-200` (age 16) and `pt-300` (penicillin allergy)
  carry demo flags purely to exercise the age-restriction and
  interaction-flag governor gates — they are not claims about any real
  person."
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

(def ^:private schema
  {:patient/id      {:db/unique :db.unique/identity}
   :item/id         {:db/unique :db.unique/identity}
   :prescription/id {:db/unique :db.unique/identity}
   :erx-network/id  {:db/unique :db.unique/identity}
   :contract/tenant {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- patient->tx [{:keys [id name age allergies]}]
  {:patient/id id :patient/name name :patient/age age :patient/allergies (enc (or allergies #{}))})

(defn- pull->patient [m]
  (when (:patient/id m)
    {:id (:patient/id m) :name (:patient/name m) :age (:patient/age m)
     :allergies (or (dec* (:patient/allergies m)) #{})}))

(def ^:private patient-pull [:patient/id :patient/name :patient/age :patient/allergies])

(defn- item->tx [{:keys [id name rx? restricted? min-age max-quantity-grams
                          schedule max-quantity-per-fill interacts-with source]}]
  (cond-> {:item/id id :item/name name :item/rx (boolean rx?) :item/restricted (boolean restricted?)
           :item/interacts-with (enc (or interacts-with #{})) :item/source (enc source)}
    min-age               (assoc :item/min-age min-age)
    max-quantity-grams    (assoc :item/max-quantity-grams (enc max-quantity-grams))
    schedule              (assoc :item/schedule schedule)
    max-quantity-per-fill (assoc :item/max-quantity-per-fill max-quantity-per-fill)))

(defn- pull->item [m]
  (when (:item/id m)
    {:id (:item/id m) :name (:item/name m) :rx? (:item/rx m) :restricted? (:item/restricted m)
     :min-age (:item/min-age m) :max-quantity-grams (dec* (:item/max-quantity-grams m))
     :schedule (:item/schedule m) :max-quantity-per-fill (:item/max-quantity-per-fill m)
     :interacts-with (or (dec* (:item/interacts-with m)) #{}) :source (dec* (:item/source m))}))

(def ^:private item-pull
  [:item/id :item/name :item/rx :item/restricted :item/min-age :item/max-quantity-grams
   :item/schedule :item/max-quantity-per-fill :item/interacts-with :item/source])

(defn- prescription->tx [{:keys [id patient-id item-id prescriber-npi verified? expired? refills-remaining]}]
  {:prescription/id id :prescription/patient-id patient-id :prescription/item-id item-id
   :prescription/prescriber-npi prescriber-npi :prescription/verified (boolean verified?)
   :prescription/expired (boolean expired?) :prescription/refills-remaining refills-remaining})

(defn- pull->prescription [m]
  (when (:prescription/id m)
    {:id (:prescription/id m) :patient-id (:prescription/patient-id m) :item-id (:prescription/item-id m)
     :prescriber-npi (:prescription/prescriber-npi m) :verified? (:prescription/verified m)
     :expired? (:prescription/expired m) :refills-remaining (:prescription/refills-remaining m)}))

(def ^:private prescription-pull
  [:prescription/id :prescription/patient-id :prescription/item-id :prescription/prescriber-npi
   :prescription/verified :prescription/expired :prescription/refills-remaining])

(defn- erx-network->tx [{:keys [network-id provider active?]}]
  {:erx-network/id network-id :erx-network/provider provider :erx-network/active (boolean active?)})

(defn- pull->erx-network [m]
  (when (:erx-network/id m)
    {:network-id (:erx-network/id m) :provider (:erx-network/provider m) :active? (:erx-network/active m)}))

(def ^:private erx-network-pull [:erx-network/id :erx-network/provider :erx-network/active])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defrecord DatomicStore [conn]
  Store
  (patient [_ id] (pull->patient (d/pull (d/db conn) patient-pull [:patient/id id])))
  (item [_ id] (pull->item (d/pull (d/db conn) item-pull [:item/id id])))
  (prescription [_ id] (pull->prescription (d/pull (d/db conn) prescription-pull [:prescription/id id])))
  (erx-network [_ network-id] (pull->erx-network (d/pull (d/db conn) erx-network-pull [:erx-network/id network-id])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
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
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
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
