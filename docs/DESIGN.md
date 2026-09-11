# Pharmacy Actor Design — PharmacyOrder-LLM as a contained intelligence node

小売薬局・OTC医薬品販売(D&B型ではなく、実店舗/オンライン薬局の調剤・販売
システム)を、governed-dispensing の運用で OSS の actor として自前運用する
ための設計。`cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor
で封じ込めた構図)を、調剤/リフィルのドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか

調剤/リフィルの正規化・開示列の提案には LLM が有効だが、LLM は次の理由で
**調剤・リフィル・開示・紛争解決の最終権限を持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 出典なしに処方箋の存在を「提案」で確定 | 無資格調剤・違法配薬 |
| 期限切れ/未検証処方箋をそのまま通す | 患者安全リスク・法令違反 |
| Schedule II 品目のリフィルを許可 | 麻薬取締法制違反 |
| 未成年への規制対象OTC販売 | 州法(疑似エフェドリン規制等)違反 |
| アレルギー/相互作用フラグを見落とす | 患者への健康被害 |

したがって設計課題は「LLM で調剤業務を回す」ことではなく、**「LLM を
信頼境界の内側に封じ込め、処方箋検証・数量上限・ライセンス・相互作用・
人間(薬剤師)レビューの層をどう被せるか」**である。

## 2. OperationActor 内部

`src/pharmacy/operation.cljk` の langgraph-clj StateGraph として実装。
**1 run = 1 操作**。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 薬剤師承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

### 2.1 注入される3つの依存(すべて swap)

- **Store**(`pharmacy.store/Store`): `MemStore`(既定)/ `DatomicStore`。
- **Advisor**(`pharmacy.llm/Advisor`): `mock-advisor`(既定)/ `llm-advisor`。
- **Phase**(`pharmacy.phase`、context の `:phase 0..3`): 段階導入。
  **`:rx/dispense`/`:rx/refill`/`:dispute/request` はどの phase の `:auto`
  にも入らない**(法的に薬剤師の最終確認が常に必要という構造的制約)。

## 3. PharmacyGovernor(独立検閲層)

`src/pharmacy/policy.cljk`。8チェック、優先順位順(上5つは HARD、人間承認
でも上書き不可):

1. **rbac**
2. **prescription-verification-gate** — 処方箋の存在・検証済み・未期限・
   (リフィルのみ)残数チェック
3. **restricted-quantity-gate**(新規、業態固有) — 品目上限超過・
   Schedule II のリフィル禁止(処方箋の残数値に関わらず構造的に強制)・
   規制対象OTCの年齢/数量制限
4. **source-provenance-gate** — 出典クラス + `:licensed-erx-network` の
   場合はアクティブな network 要求
5. **licensed-disclosure** — 契約 tier 超過列の拒否
6. 確信度フロア(soft)
7. **interaction-flag gate**(soft) — 患者アレルギー×品目相互作用の
   フラグが立てば必ず薬剤師承認
8. **dispute requests**(soft、無条件) — 紛争申立ては常に人間レビュー

**意図的に無い項目**: 与信/カウンターパーティチェック相当は無い —
この actor は調剤可否判断と記録のみを行い、注文執行・配送・決済を
一切含まない。

## 4. SSoT と監査台帳

`src/pharmacy/store.cljk`。entities: `patients`(年齢+アレルギーのみ、
広範な医療記録ではない) `items`(OTC/Rx区分、DEAスケジュール、規制OTC
制限) `prescriptions`(検証状態+残数) `erx-networks`(取込ライセンス)
`contracts`(subscriber licensing)。

## 5. R0 の正直なスコープ(捏造禁止)

`src/pharmacy/facts.cljk`。実在する3つの自由公式参照ソース(FDA NDC
Directory、DEA Controlled Substance Schedules、NPPES NPI Registry)+
1つの構造的クラス `:licensed-erx-network`(処方箋の実在性そのものは
operator が自前のライセンス済み e-prescribing/PDMP ネットワークを登録
して初めて取込可能)。`facts/coverage` が常に正直に現状を報告する。

## 6. デモ(`kbb -M:dev:run`)

`src/pharmacy/sim.cljk` が10操作を actor に通す(§sim.cljc docstring
参照): 正当なOTC調剤 → commit、出典なし/改ざん疑い → hold ×2、
期限切れ処方箋 → hold、数量上限超過/Schedule II リフィル禁止 → hold ×2、
未成年への規制OTC → hold、アレルギー相互作用 → 薬剤師承認 → commit、
tier超過開示 → hold、紛争申立て → 薬剤師承認 → commit。

## 7. テスト(`kbb -M:dev:test`)

`test/pharmacy/policy_contract_test.cljk` がガバナンス契約を実行可能に
する。`test/pharmacy/phase_test.cljk` が「Rx調剤はどの phase でも
auto化されない」ことを保証。`test/pharmacy/facts_test.cljk` が出典
カタログ自体の正直さ(捏造禁止)を保証。

## 8. 実装と業態の対応

| 実在業態の機能 | pharmacy actor での実体 |
|---|---|
| 処方箋検証 | `prescription-verification-gate` + `erx-networks` |
| 麻薬取締スケジュール管理 | `restricted-quantity-gate` + DEA schedule reference |
| 疑似エフェドリン販売規制 | `restricted-quantity-gate`(OTC年齢/数量) |
| 薬剤師の最終確認 | 恒久 human-only ゲート(`:rx/*` never auto) |
| 相互作用/アレルギーチェック | interaction-flag gate |
| 患者紛争/有害事象報告 | `:dispute/request`(恒久 human-only) |
| 監査台帳 | `store` append-only ledger |
