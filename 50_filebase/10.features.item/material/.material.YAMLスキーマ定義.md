# MATERIAL (素材) YAML スキーマ定義

素材アイテムの基本的なスキーマ定義。  
共通スキーマ（`item.YAMLスキーマ定義.md`）を継承します。カテゴリ固有のサブセクションはありません。

## スキーマ定義

共通スキーマの全プロパティに加え、以下を参照してください。

| キー         | 型       | 必須 | デフォルト | 説明           |
|:-----------|:--------|:--:|-------|:-------------|
| `maxStack` | Integer | ×  | 64    | アイテムの最大スタック数 |


## YAML 例

```yaml
schemaVersion: 1
id: iron_ingot
category: material
name: 鉄インゴット
icon: IRON_INGOT
rarity: COMMON
maxStack: 64
saleValue: 10
lore:
  - 基本的な素材として使用される鉄のインゴット。
  - 武器や防具の作成に欠かせない。
unTradeable: false
unSellable: false
```
