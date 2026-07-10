(ns pharmacy.llm-test
  "PharmacyOrder-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [pharmacy.store :as store]
            [pharmacy.llm :as llm]))

(deftest otc-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :otc/dispense :subject "pt-100" :item-id "item-otc-100"
                         :quantity-grams 0.4M
                         :source {:class :fda-drug-registry :ref "demo"}})]
    (is (= :otc-dispense-log (:effect p)))
    (is (= {:class :fda-drug-registry :ref "demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-otc-proposal-carries-nil-source
  (testing "the LLM layer does not filter — that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :otc/dispense :subject "pt-100" :item-id "item-otc-100"
                           :quantity-grams 0.4M
                           :source {:class :fda-drug-registry :ref "demo"} :unsourced? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85) "still high-confidence — proves source-provenance cannot rely on confidence as a proxy"))))

(deftest leaky-rx-proposal-drops-network-id
  (testing "the LLM layer does not filter — proves the injected failure mode (missing network-id) actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :rx/dispense :subject "pt-100" :prescription-id "rx-100" :item-id "item-rx-100"
                           :quantity 20
                           :source {:class :licensed-erx-network :ref "demo" :network-id "net-demo"}
                           :leaky? true})]
      (is (not (contains? (:source p) :network-id)))
      (is (>= (:confidence p) 0.9)))))

(deftest refill-proposal-carries-quantity
  (let [db (store/seed-db)
        p (llm/infer db {:op :rx/refill :subject "pt-100" :prescription-id "rx-100" :item-id "item-rx-100"
                         :quantity 20
                         :source {:class :licensed-erx-network :ref "demo" :network-id "net-demo"}})]
    (is (= :prescription-refill-apply (:effect p)))
    (is (= 20 (get-in p [:value :quantity])))))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :report/query :subject "pt-100"})
        greedy (llm/infer db {:op :report/query :subject "pt-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:prescriber-npi :refills-remaining} (:columns greedy)))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :dispute/request :subject "rx-100" :disputed-field :quantity-dispensed :claim :incorrect})]
    (is (= :dispute-apply (:effect p)))
    (is (< (:confidence p) 0.9) "disputes are claims pending pharmacist verification, never auto-confident")))
