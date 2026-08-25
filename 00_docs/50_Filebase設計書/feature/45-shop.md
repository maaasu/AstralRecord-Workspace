# Shop 設計

## 役割

Shop は、通貨や item を対価として、購入、売却、交換を提供する定義です。

## 設計方針

- 初期供給、救済、素材消費、交換のどの役割を持つか明確にします。
- loot や recipe の価値を失わせる常時販売を避けます。
- 買値、売値、交換比率は、同じ progression の入手時間と比較します。
- NPC や導線から参照されない shop を作りません。
`skill_gem_exchange` の1pageは、初級から神級までの上位原石1個を直下位原石3個へ変換する7段階を扱います。逆方向の交換は持ちません。2pageは原則として無印原石1個から各自動生成スキルジェム1個への交換を扱います。既存例外の `administrator_shield_recharge` は、上級原石1個からジェム1個への交換を維持します。初級原石からジェムへの交換は持ちません。

## progression

shop を最初に利用可能になる段階を記載します。商品ごとの progression は維持し、shop の progression だけで商品価値を上書きしません。

## 正本参照

- 戦闘・ゲームバランス: 装備・skill・消耗品などの供給量、価格、更新時期に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\45.features.shop\docs.shop.YAMLスキーマ定義.md`
