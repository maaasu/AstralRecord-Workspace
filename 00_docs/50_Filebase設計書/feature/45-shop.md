# Shop 設計

## 役割

Shop は、通貨や item を対価として、購入、売却、交換を提供する定義です。

## 設計方針

- 初期供給、救済、素材消費、交換のどの役割を持つか明確にします。
- loot や recipe の価値を失わせる常時販売を避けます。
- 買値、売値、交換比率は、同じ progression の入手時間と比較します。
- NPC や導線から参照されない shop を作りません。

## progression

shop を最初に利用可能になる段階を記載します。商品ごとの progression は維持し、shop の progression だけで商品価値を上書きしません。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\45.features.shop\shop.YAMLスキーマ定義.md`
