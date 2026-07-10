# ADR-0001: cloud-itonami-isic-4772 — PharmacyOrder-LLM を封じ込めた知能ノードとする薬局調剤アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図の直接の手本)、`cloud-itonami-isic-7820`(default-phase=1
  を初期実装時点から採用する fail-open 対策の手本)
- 文脈: com-junkawasaki/root superproject ADR-2607114772(本 ADR の対)

## 課題

`kotoba-lang/industry` registry の未着手 `:spec` スロットから、ISIC Rev.4
4772「Retail sale of pharmaceutical and medical goods, cosmetic and
toilet articles」を選定した。オンライン/実店舗薬局の調剤・OTC販売業態で
あり、実定法上の制約(処方箋要件・麻薬取締スケジュール・規制対象OTCの
年齢/数量制限)が極めて明確な、フリート内でも屈指の「なぜLLMに直接
やらせてはいけないか」の説得力を持つドメインである。

## 決定

### 1. PharmacyOrder-LLM は最下層の1ノードに封じ込め、直接調剤/リフィル/開示/紛争解決させない

> **PharmacyOrder-LLM は、PharmacyGovernor が拒否する調剤・リフィル・
> 開示・紛争解決を決して行わない。**

### 2. PharmacyGovernor は8チェック(HARD5 + SOFT3)

新規 HARD チェック2つ:
- **prescription-verification-gate**: 処方箋の存在・検証済み・未期限・
  (リフィルのみ)残数を確認。
- **restricted-quantity-gate**: 品目の per-fill 上限、Schedule II の
  リフィル禁止(処方箋レコードの残数値に関わらず構造的に強制する
  defense-in-depth)、規制対象OTCの年齢/数量制限。

### 3. `:rx/dispense`/`:rx/refill` は Phase 3 でも auto 化しない

`cloud-itonami-isic-6311`/`isic-7820` の「govener-clean なら auto-commit
可能」パターンを踏襲しつつ、Rx調剤に限っては governor が完全にクリーンで
あっても**構造的に**人間(薬剤師)承認を要求する — 処方箋薬の最終確認は
薬剤師の法的義務であり、rollout の成熟度で解除できる性質のものではない
という判断。OTC(規制なし品目)のみ Phase 3 で auto-commit 可能。

### 4. default-phase = 1 を初期実装から採用

`cloud-itonami-isic-7820` で確立された fail-open 対策(`:phase` を省略
した呼び出し元は最も保守的な phase を得る)を、後から修正するのではなく
最初から正しい設計として採用した。

### 5. R0 の正直なスコープ

FDA NDC Directory・DEA Controlled Substance Schedules・NPPES NPI
Registry の3実在無料公式参照ソース + 構造的 `:licensed-erx-network`
クラス(処方箋の実在性そのものは operator が自前のライセンス済み
e-prescribing/PDMP ネットワークを登録して初めて取込可能)。

## Consequences

- (+) `kotoba-lang/industry` registry の 4772 スロットが実装へ昇格
  (6件目の spec→implemented 昇格)。
- (+) `clojure -M:dev:test`: 39 tests / 138 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。`clojure -M:dev:run` デモも
  end-to-end で確認済み(10シナリオ全て正しく発火)。
- (+) DatomicStore の contract test 実装中に実バグを発見・修正:
  `:dispute-apply` のパッチフィールドが Datomic 側の固定スキーマに
  含まれない任意キーだと MemStore では通るが Datomic では黙って消える
  ことが判明し、既存スキーマフィールド(`:verified?`)を使う設計に
  修正した(dossier系の correction-apply パターンにも一般化しうる
  教訓)。
- (-) R0 の自由公式ソースは3種のみ。処方箋の実在性そのものは operator の
  erx-network 登録が必須。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。

## 代替案と不採用理由

- **Rx調剤も Phase 3 で auto-commit 可能にする**: governor-clean なら
  安全という前提は、薬剤師の法的最終確認義務という別レイヤーの要件を
  見落とす。構造的に禁止するのが正確。
- **restricted-quantity-gate を SOFT にとどめる**: 麻薬取締法制違反や
  未成年への規制品販売は高確信のまま起こりうるため、SOFT(確信度フロア
  依存)では防げない。HARD が必須。

## References

- `README.md` / `docs/business-model.md` / `docs/DESIGN.md`
- `90-docs/adr/2607114772-cloud-itonami-isic-4772-pharmacy-retail-actor.md`
  (superproject 側 ADR、本 ADR と対)
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`
  (id "4772" エントリ)
