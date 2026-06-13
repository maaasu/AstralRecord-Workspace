# Harvesting YAML スキーマ定義

採取カテゴリの採集オブジェクト定義です。
共通キーは [../gathering.YAMLスキーマ定義.md](../gathering.YAMLスキーマ定義.md) を参照してください。

## 追加ルール

- `category` は `HARVESTING` 固定
- `requiredToolTags[]` には `HOE` など採取用タグを定義する
- `displayBlock` は草木・作物・葉系の Material を想定する

## YAML 例

```yaml
schemaVersion: 1
id: herb_bush
type: GATHERING
category: HARVESTING
name: "&a薬草の茂み"
maxHealth: 40
displayBlock: AZALEA_LEAVES
displayScale:
  x: 0.9
  y: 0.8
  z: 0.9
requiredToolTags:
  - HOE
```
