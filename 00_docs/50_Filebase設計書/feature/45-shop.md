# Shop 設計

## 役割

Shop は、通貨や item を対価として、購入、売却、交換を提供する定義です。

## 設計方針

- 初期供給、救済、素材消費、交換のどの役割を持つか明確にします。
- loot や recipe の価値を失わせる常時販売を避けます。
- 買値、売値、交換比率は、同じ progression の入手時間と比較します。
- NPC や導線から参照されない shop を作りません。

`astrald_shop` は、1行目のslot 0でストレージ拡張トークンを100アストラルド、slot 1でストレージ遠隔アクセストークンを300アストラルドで販売します。2行目のslot 7〜10にはマーケット拡張トークンα／β／γ／δをそれぞれ50／100／150／200アストラルドで配置します。いずれもGoldを対価に使用しません。

`skill_gem_raw_exchange` は、初級から神級までの上位原石1個を直下位原石3個へ変換する7段階を扱います。逆方向の交換は持ちません。スキルジェムとスキルジェム交換所は廃止し、スキルマネージャーが各 skill master の `learnRequiredItems` / `levelUpRequiredItems` を正本として原石を消費します。

### 廃止した skill_gem_exchange

旧 `skill_gem_exchange` の職業別価格は各 skill master へ移行済みです。

## progression

shop を最初に利用可能になる段階を記載します。商品ごとの progression は維持し、shop の progression だけで商品価値を上書きしません。

## 正本参照

- 戦闘・ゲームバランス: 装備・skill・消耗品などの供給量、価格、更新時期に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\45.features.shop\docs.shop.YAMLスキーマ定義.md`
