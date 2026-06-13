# Gathering 共通 YAML スキーマ定義

採集オブジェクトの共通定義です。
`40.features.mob` と同様にカテゴリ別ファイルで管理し、`mining` と `harvesting` を split source として Seeder が読み込みます。

## カテゴリ

| カテゴリ | 配置ディレクトリ | 用途 |
|:--|:--|:--|
| `MINING` | `mining/` | ツルハシ等で破壊する採掘オブジェクト |
| `HARVESTING` | `harvesting/` | クワ等で破壊する採取オブジェクト |

## スキーマ

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | - | スキーマバージョン。現在は `1` |
| `id` | String | ○ | - | 採集オブジェクト ID（例: `iron_ore_vein`） |
| `type` | String | ○ | - | 種別。`GATHERING` 固定 |
| `category` | String | ○ | - | カテゴリ。`MINING` / `HARVESTING` |
| `name` | String | ○ | - | ゲーム内表示名。カラーコード可 |
| `maxHealth` | Integer | ○ | - | 採集オブジェクトの HP |
| `displayBlock` | String | ○ | - | DisplayBlock に使う Bukkit Material 名 |
| `displayScale` | Map | ○ | - | DisplayBlock の拡大率 |
| `displayScale.x` | Double | ○ | - | X 軸スケール |
| `displayScale.y` | Double | ○ | - | Y 軸スケール |
| `displayScale.z` | Double | ○ | - | Z 軸スケール |
| `requiredToolTags[]` | List<String> | ○ | - | 破壊可能な装備タグ。`PICKAXE` / `HOE` など |

## requiredToolTags

`requiredToolTags[]` は item/equipment の `equipment.tag` と対応する値を保持します。
採集判定では、このリストのいずれかに一致する装備だけを有効な採集ツールとして扱います。

例:

- `PICKAXE`
- `HOE`
- `AXE`
- `SHOVEL`
- `SHEARS`

## YAML 例

```yaml
schemaVersion: 1
id: iron_ore_vein
type: GATHERING
category: MINING
name: "&7鉄鉱脈"
maxHealth: 80
displayBlock: IRON_ORE
displayScale:
  x: 1.0
  y: 1.0
  z: 1.0
requiredToolTags:
  - PICKAXE
```

## drops

`drops` は採集オブジェクト破壊時のドロップ候補です。形式は Mob の `drops.items` と同じです。

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `drops.exp` | Integer | 任意 | `0` | 破壊時の経験値。現時点では表示結果用 |
| `drops.items[]` | List | 任意 | empty | アイテムドロップ候補 |
| `drops.items[].itemId` | String | 任意 | - | item ID。`item:` prefix 可 |
| `drops.items[].rate` | Double | 任意 | `0.0` | ドロップ率 `0.0-100.0` |
| `drops.items[].amount` | String | 任意 | `"1"` | 固定個数または `1~3` 形式 |
| `drops.items[].luckAffected` | Boolean | 任意 | `true` | LUCK 補正対象か |
| `drops.items[].hidden` | Boolean | 任意 | `false` | 将来の表示制御用 |

```yaml
drops:
  exp: 0
  items:
    - itemId: item:iron_ingot
      rate: 100.0
      amount: "1~2"
      luckAffected: false
      hidden: false
```
