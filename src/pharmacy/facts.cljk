(ns pharmacy.facts
  "R0 source-basis catalog — the ONLY provenance classes the
  PharmacyGovernor will accept as a citation for a dispensing or refill
  request (mirrors `cloud-itonami-isic-6311`'s `marketdata.facts`
  discipline: honesty over coverage). Three kinds of entry:

    1. FDA National Drug Code (NDC) Directory — real, public, free API.
       Grounds 'this is a real, correctly classified drug/NDC code,
       including its Rx/OTC status'.
    2. DEA Controlled Substance Schedules — real, public reference
       (published list, not a live API). Grounds the schedule (II-V)
       assigned to a controlled item, and therefore its quantity/refill
       ceiling.
    3. NPPES NPI Registry — real, public, free API. Grounds a
       prescriber's identity/license as a real, active National
       Provider Identifier.

  `:licensed-erx-network` is the *structural* class for the actual
  transactional fact 'this prescription exists and is valid right now' —
  no free public source can grant that (it lives in a PDMP / e-prescribing
  network, e.g. Surescripts-class integrations). A dispense/refill citing
  this class is only accepted when it also carries a `:network-id` that
  resolves to an ACTIVE `erx-network` record in the store — the same
  operator-supplies-their-own-licensed-integration boundary as
  `cloud-itonami-isic-6311`'s `:licensed-operator-feed` /
  `kotoba-lang/securities`'s 'operator supplies own licensed feed'.")

(def catalog
  "Each entry: {:id :name :class :access :url}. `:class` is the value that
  must appear in a request's `:source :class` for the source-provenance
  gate to accept it as grounded."
  [{:id :fda-ndc-directory
    :name "FDA National Drug Code (NDC) Directory"
    :class :fda-drug-registry :access :public-api
    :url "https://open.fda.gov/apis/drug/ndc/"}
   {:id :dea-schedule-reference
    :name "DEA Controlled Substance Schedules"
    :class :dea-schedule-reference :access :public-reference
    :url "https://www.deadiversion.usdoj.gov/schedules/"}
   {:id :nppes-npi-registry
    :name "NPPES NPI Registry"
    :class :npi-registry :access :public-api
    :url "https://npiregistry.cms.hhs.gov/api-page"}
   {:id :licensed-erx-network
    :name "Operator-registered e-prescribing / PDMP network integration"
    :class :licensed-erx-network :access :operator-licensed
    :url nil}])

(def allowed-source-classes
  (into #{} (map :class catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全処方箋ネットワーク' in prose, 3 free reference sources + 1
  structural licensed-network class in fact)."
  []
  {:source-count (count catalog)
   :free-public-sources (into #{} (map :id (filter #(#{:public-api :public-reference} (:access %)) catalog)))
   :note (str "R0 scope: 3 free public reference sources (FDA NDC directory, "
              "DEA schedule reference, NPPES NPI registry) + 1 structural "
              "licensed-erx-network class for the actual prescription-exists "
              "fact. Extend only by appending a real, citable catalog entry "
              "or a real registered erx-network — never fabricate either.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn licensed-network-class? [source-class]
  (= :licensed-erx-network source-class))
