# Mining YAML スキーマ定義

採掘カテゴリの採集オブジェクト定義です。
共通キーは [../docs.gathering.YAMLスキーマ定義.md](../docs.gathering.YAMLスキーマ定義.md) を参照してください。

## スキーマ定義

### 追加ルール

- `category` は `MINING` 固定
- `requiredToolTags[]` には `PICKAXE` を含める前提で定義する
- `displayBlock` は鉱石・岩石系の Material を想定する

## YAML 例

```yaml
schemaVersion: 1
id: copper_ore_vein
type: GATHERING
category: MINING
name: "&6銅鉱脈"
maxHealth: 60
displayBlock: COPPER_ORE
displayScale:
  x: 1.0
  y: 1.0
  z: 1.0
requiredToolTags:
  - PICKAXE
```
