(ns pharmacy.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the PharmacyGovernor's licensed-disclosure
  gate approved for the caller's contract tier (see `:report/query`).
  This namespace only renders the approved columns, so a disclosure can
  never exceed the licensed tier."
  (:require [pharmacy.store :as store]))

(defn render-status
  "Render one patient's most-recent prescription status over exactly
  `columns` (already governor-approved)."
  [db patient-id prescription-id columns]
  (let [rx (store/prescription db prescription-id)
        it (when rx (store/item db (:item-id rx)))
        cell (fn [col]
               (case col
                 :patient-id        patient-id
                 :item-id           (:id it)
                 :status            (cond (nil? rx) :unknown
                                          (:expired? rx) :expired
                                          (:verified? rx) :active
                                          :else :unverified)
                 :as-of             (:id rx)
                 :prescriber-npi    (:prescriber-npi rx)
                 :refills-remaining (:refills-remaining rx)
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
