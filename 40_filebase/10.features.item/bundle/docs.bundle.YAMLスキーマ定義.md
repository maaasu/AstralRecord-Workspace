# Bundle (パッケージ) YAML スキーマ定義

パッケージアイテムの基本スキーマ定義です。

共通のアイテムフィールド `schemaVersion` / `id` / `category` / `name` などは `docs.item.YAMLスキーマ定義.md` を参照してください。ここでは Bundle 固有フィールドのみを定義します。

Bundle の中身の指定方法は 2 系統あります。

| 方式 | 説明 |
|:--|:--|
| `bundle.lootTableId` | 既存の LootTable を参照して中身を決定する |
| `bundle.items[]` | bundle 側に報酬アイテムを直接定義する |

両方を定義した場合も併用可能です。

---

## スキーマ定義

| キー | 型 | 必須 | 既定値 | 説明 |
|:--|:--|:--:|:--|:--|
| `maxStack` | Integer | × | 64 | アイテムの最大スタック数 |
| `bundle.lootTableId` | String | × | Null | LootTableId の参照先 ID |
| `bundle.items[]` | List | × | Null | 報酬アイテムの直接定義 |
| `bundle.gold` | Integer | × | `0` | 開封時に加算するGold |
| `bundle.openTimeTicks` | Long | × | `20` | 開封完了まで静止する必要がある時間（tick 単位。20 tick = 1 秒。1 以上） |
| `bundle.onUse.sound` | Map | × | 既定値 | bundle 内に Sound 実体を定義 |
| `bundle.onUse.particle` | Map | × | 既定値 | bundle 内に Particle 実体を定義 |

`sound` と `particle` は bundle の `onUse` 内に直接定義します。未指定、または内容を解決できない場合は、プラグインの bundle 既定値（`block.chest.open` / `TOTEM_OF_UNDYING`）を使用します。

```yaml
bundle:
  openTimeTicks: 20
  lootTableId:
    ref: loot_table:example_table
  onUse:
    sound:
      sound: block.chest.open
      volume: 0.6
      pitch: 1.28
    particle:
      particle: TOTEM_OF_UNDYING
      count: 24
      originOffsetY: 1.0
      offsetX: 0.4
      offsetY: 0.5
      offsetZ: 0.4
```

### bundle.items[]

`bundle.lootTableId` の代わりに、または併用して報酬アイテムを直接定義できます。

| キー | 型 | 必須 | 既定値 | 説明 |
|:--|:--|:--:|:--|:--|
| `bundle.items[].itemId` | String | ○ | - | アイテム ID。例: `ref: item:iron_ingot` |
| `bundle.items[].amount` | String | × | 1 | 個数。例: `1` / `1~3` |
| `bundle.items[].rate` | Double | × | 100.0 | 抽選確率 |
| `bundle.items[].luckAffected` | Boolean | × | false | `true` の場合は luck 補正対象 |
| `bundle.items[].hidden` | Boolean | × | false | `true` の場合は開封前の表示から隠す |

現行Pluginが直接報酬として解釈するのは `itemId` と固定の正整数 `amount` です。`rate`、`luckAffected`、`hidden` は将来の抽選表示拡張用であり、確率報酬には `lootTableId` を使用します。

---

## YAML 例

以下の Bundle ID は新規定義を示す架空例です。LootTable と Item の参照には現行マスタの実在 ID を使用しています。

### LootTable 参照

```yaml
schemaVersion: 1
id: example_windwait_loot_bundle
category: BUNDLE
name: "&bウィンドウェイトパケット"
icon: CHEST
rarity: UNCOMMON
lore:
  - "&7風待ち草原の報酬をまとめた簡易パケット。"
unTradeable: false
bundle:
  lootTableId:
    ref: loot_table:windwait_field_table
```

### 報酬アイテム直接定義

```yaml
schemaVersion: 1
id: example_adventure_supply_bundle
category: BUNDLE
name: "&e冒険補給パック"
icon: CHEST
rarity: COMMON
maxStack: 1
lore:
  - "&7冒険に必要な消耗品をまとめた補給物資。"
unTradeable: true
unSellable: true
bundle:
  items:
    - itemId:
        ref: item:healing_potion_small
      amount: 3
    - itemId:
        ref: item:iron_ingot
      amount: 5
    - itemId:
        ref: item:bronze_sword
      amount: 1
    - itemId:
        ref: item:astral_dust
      amount: 1
      rate: 10.0
      luckAffected: true
      hidden: true
```

### LootTable と直接定義の併用

```yaml
schemaVersion: 1
id: example_mixed_reward_bundle
category: BUNDLE
name: "&6冒険者の支援箱"
icon: CHEST
rarity: RARE
maxStack: 1
lore:
  - "&7冒険者向けの支援箱。何が入っているかは開けてのお楽しみ。"
unTradeable: true
bundle:
  lootTableId:
    ref: loot_table:windwait_field_table
  items:
    - itemId:
        ref: item:healing_potion_small
      amount: 1
```

> Sound / Particle 専用マスタは設けません。演出定義は `bundle.onUse` 内に記述してください。
