(ns pharmacy.llm
  "PharmacyOrder-LLM client — the *contained intelligence node*.

  It normalizes OTC/Rx dispense and refill requests, proposes disclosure
  column sets for a licensed subscriber query, and drafts dispensing-
  dispute resolution notes. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields/source
  it cited), never a committed dispense, refill or disclosure. Every
  output is censored downstream by `pharmacy.policy` (the
  PharmacyGovernor) before anything is dispensed, logged or disclosed.

  Like `cloud-itonami-isic-6311`'s MarketData-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the source-provenance gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str :network-id str?}|nil ; SCANNED
     :effect     kw             ; how a commit would mutate the SSoT/ledger
     :value      map|nil        ; the record patch, for refill/dispute
     :columns    [kw ..]|nil    ; proposed disclosure column set
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [pharmacy.store :as store]))

(defn- propose-otc-dispense
  "OTC dispense — the LLM only normalizes the request (adds no new
  provenance). `:unsourced?` injects the failure mode we must defend
  against: a dispense arriving with no source citation at all — the
  PharmacyGovernor's source-provenance-gate must reject this outright,
  regardless of how confident the LLM is."
  [_db {:keys [item-id source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "OTC dispense: " item-id)
     :rationale "出典引用済みNDC分類の正規化のみ。新規事実の生成なし。"
     :cites     [:item-id :quantity-grams]
     :source    src
     :effect    :otc-dispense-log
     :value     nil
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-rx-dispense
  "Initial Rx fill against an already-verified prescription. `:leaky?`
  injects the failure mode this actor exists to catch: a fill proposal
  citing a `:licensed-erx-network` source whose `:network-id` is
  deliberately wrong/missing — the source-provenance-gate must reject it
  regardless of confidence."
  [_db {:keys [prescription-id item-id source leaky?]}]
  (let [src (if leaky? (dissoc source :network-id) source)]
    {:summary   (str "Rx dispense: " prescription-id " (" item-id ")")
     :rationale "検証済み処方箋の初回調剤。新規事実の生成なし。"
     :cites     [:prescription-id :item-id :quantity]
     :source    src
     :effect    :rx-dispense-log
     :value     nil
     :confidence 0.95}))

(defn- propose-rx-refill
  [_db {:keys [prescription-id item-id quantity source]}]
  {:summary   (str "Rx refill: " prescription-id " (" item-id ")")
   :rationale "処方箋のリフィル残数消費。"
   :cites     [:prescription-id :item-id :quantity]
   :source    source
   :effect    :prescription-refill-apply
   :value     {:prescription-id prescription-id :quantity quantity}
   :confidence 0.9})

(defn- propose-disclosure
  "Disclosure column-set proposal for a licensed subscriber query.
  `:greedy?` injects over-disclosure — the PharmacyGovernor's
  licensed-disclosure gate must reject the excess columns."
  [_db {:keys [subject greedy?]}]
  (let [base [:patient-id :item-id :status :as-of]
        greedy-extra [:prescriber-npi :refills-remaining]]
    {:summary   (str "開示列提案: " subject)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-dispute
  "Dispensing-dispute/adverse-event resolution draft. This NEVER
  auto-applies — `pharmacy.policy` and `pharmacy.phase` both structurally
  force every `:dispute/request` to human (pharmacist) review, independent
  of confidence."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "処方箋の " disputed-field " について申立てへの解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは薬剤師レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :dispute-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  [db {:keys [op] :as request}]
  (case op
    :otc/dispense        (propose-otc-dispense db request)
    :rx/dispense          (propose-rx-dispense db request)
    :rx/refill             (propose-rx-refill db request)
    :report/query         (propose-disclosure db request)
    :dispute/request      (propose-dispute db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  []
  (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは薬局の調剤・リフィル・開示アドバイザーです。"
       "与えられた事実のみに基づき、提案を1つだけ EDN マップで返します。"
       "説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :source({:class .. :ref .. :network-id? ..}か nil) "
       ":effect(:otc-dispense-log|:rx-dispense-log|:prescription-refill-apply|"
       ":disclosure-serve|:dispute-apply) :value(該当マップ) :confidence(0..1)。\n"
       "重要: 出典を伴わない調剤・リフィルは絶対に提案してはいけません。"
       "処方箋の有効性判断・数量上限判断・年齢制限判断・アレルギー/相互作用判断は"
       "あなたの責務ではありません(governor が判定します)。"))

(defn- facts-for [st {:keys [op subject item-id prescription-id]}]
  (case op
    :report/query {:patient (store/patient st subject)}
    {:item (store/item st item-id) :prescription (when prescription-id (store/prescription st prescription-id))}))

(defn- parse-proposal
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  [request proposal]
  {:t          :pharmacyllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
