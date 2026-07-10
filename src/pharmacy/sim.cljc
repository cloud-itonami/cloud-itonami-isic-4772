(ns pharmacy.sim
  "Demo runner: push nine representative operations through one
  OperationActor and watch the PharmacyGovernor + approval workflow earn
  the PharmacyOrder-LLM the right to dispense, refill or resolve a
  dispute.

    op1  OTC調剤(出典あり・制限なし品目)                   → commit
    op2  OTC調剤が出典なし                                 → source-provenance REJECT → hold
    op3  Rx調剤の出典が network-id 欠落(改ざん疑い)        → source-provenance REJECT → hold
    op4  期限切れ処方箋への調剤                            → prescription-verification REJECT → hold
    op5  リフィル数量が品目上限を超過                      → restricted-quantity REJECT → hold
    op6  Schedule II 品目のリフィル(残数はあっても法定禁止) → restricted-quantity REJECT → hold
    op7  規制対象OTC(疑似エフェドリン)を未成年へ            → restricted-quantity REJECT → hold
    op8  アレルギー既往のある患者への該当品目調剤          → 薬剤師承認へ escalate → approve → commit
    op9  開示クエリ(tier/basic 契約なのに拡張列を要求)      → licensed-disclosure REJECT → hold
    op10 調剤紛争の申立て(どの phase でも常に人間レビュー) → escalate → approve → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [pharmacy.store :as store]
            [pharmacy.operation :as op]
            [pharmacy.facts :as facts]
            [pharmacy.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  薬剤師レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "pharmacist-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        clerk      {:actor-id "cl-1" :actor-role :otc-clerk :phase 3}
        pharmacist {:actor-id "ph-1" :actor-role :pharmacist :phase 3}
        subscriber {:actor-id "sub-1" :actor-role :subscriber}]

    (line "── R0 出典カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (PharmacyOrder-LLM sealed; PharmacyGovernor active) ──")

    (line "\nop1  OTC調剤(出典あり・制限なし品目)")
    (run-op! actor "op1"
             {:op :otc/dispense :subject "pt-100" :item-id "item-otc-100"
              :quantity-grams 0.4M
              :source {:class :fda-drug-registry :ref "ndc:demo-otc-100"}}
             clerk true)

    (line "\nop2  OTC調剤が出典なし")
    (run-op! actor "op2"
             {:op :otc/dispense :subject "pt-100" :item-id "item-otc-100"
              :quantity-grams 0.4M :unsourced? true}
             clerk true)

    (line "\nop3  Rx調剤の出典が network-id 欠落(改ざん疑い)")
    (run-op! actor "op3"
             {:op :rx/dispense :subject "pt-100" :prescription-id "rx-100" :item-id "item-rx-100"
              :quantity 20
              :source {:class :licensed-erx-network :ref "net-demo:rx-100"} :leaky? true}
             pharmacist true)

    (line "\nop4  期限切れ処方箋への調剤")
    (run-op! actor "op4"
             {:op :rx/dispense :subject "pt-100" :prescription-id "rx-expired" :item-id "item-rx-100"
              :quantity 20
              :source {:class :licensed-erx-network :ref "net-demo:rx-expired" :network-id "net-demo"}}
             pharmacist true)

    (line "\nop5  リフィル数量が品目上限を超過")
    (run-op! actor "op5"
             {:op :rx/refill :subject "pt-100" :prescription-id "rx-100" :item-id "item-rx-100"
              :quantity 50
              :source {:class :licensed-erx-network :ref "net-demo:rx-100" :network-id "net-demo"}}
             pharmacist true)

    (line "\nop6  Schedule II 品目のリフィル(残数はあっても法定禁止)")
    (run-op! actor "op6"
             {:op :rx/refill :subject "pt-100" :prescription-id "rx-200b" :item-id "item-rx-200"
              :quantity 10
              :source {:class :licensed-erx-network :ref "net-demo:rx-200b" :network-id "net-demo"}}
             pharmacist true)

    (line "\nop7  規制対象OTC(疑似エフェドリン)を未成年へ")
    (run-op! actor "op7"
             {:op :otc/dispense :subject "pt-200" :item-id "item-otc-200"
              :quantity-grams 2.0M
              :source {:class :fda-drug-registry :ref "ndc:demo-otc-200"}}
             clerk true)

    (line "\nop8  アレルギー既往のある患者へ相互作用品目を調剤(出典・数量は正常でも薬剤師承認)")
    (run-op! actor "op8"
             {:op :rx/dispense :subject "pt-300" :prescription-id "rx-300" :item-id "item-rx-300"
              :quantity 14
              :source {:class :licensed-erx-network :ref "net-demo:rx-300" :network-id "net-demo"}}
             pharmacist true)

    (line "\nop9  開示クエリ(tier/basic 契約なのに拡張列を要求)")
    (run-op! actor "op9"
             {:op :report/query :subject "pt-100" :greedy? true}
             (assoc subscriber :tenant "tenant-basic") true)

    (line "\nop10 調剤紛争の申立て(どの phase でも常に人間レビュー — 検証状態を取消し再レビューへ)")
    (run-op! actor "op10"
             {:op :dispute/request :subject "rx-100" :disputed-field :verified? :claim false}
             pharmacist true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-status db "pt-100" "rx-100" [:patient-id :item-id :status :as-of])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの処方箋/出典で調剤/開示したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
