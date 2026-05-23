# Bundle (パッケージ) YAML スキーマ定義

パッケージアイテムの基本的なスキーマ定義。

共通のアイテムフィールド（`schemaVersion` / `id` / `category` / `name` など）は `item.YAMLスキーマ定義.md` を参照し、本書では Bundle 固有フィールドのみを定義します。

Bundle の中身の指定方法は以下の2種類があります。

| 方式                   | 説明                                         |
|:---------------------|:-------------------------------------------|
| `bundle.lootTableId` | 既存の LootTable を参照して中身を決定する                 |
| `bundle.items[]`     | 個別にアイテムを直接指定する（mob の drops.items[] と同様の形式） |

両方を同時に指定することも可能です。その場合、両方の結果が合算されます。

---

## スキーマ定義

| キー                          | 型       | 必須 | デフォルト | 説明                          |
|:----------------------------|:--------|:--:|:------|:----------------------------|
| `maxStack`                  | Integer | ×  | 64    | アイテムの最大スタック数                |
| `bundle.lootTableId`        | String  | ×  | Null  | LootTableId。参照値             |
| `bundle.items[]`            | List    | ×  | Null  | 個別アイテム指定リスト（後述）             |
| `bundle.onUse.sound`        | String  | ×  | Null  | 使用時に鳴るサウンド                  |
| `bundle.onUse.particle`     | String  | ×  | Null  | 使用時のパーティクル                  |

### bundle.items[]（個別アイテム指定）

`bundle.lootTableId` の代わりに、または併用して個別にアイテムを指定できます。

| キー                            | 型       | 必須 | デフォルト | 説明                                                   |
|:------------------------------|:--------|:--:|:------|:-----------------------------------------------------|
| `bundle.items[].itemId`       | String  | ○  | -     | 付与するアイテムのID（参照値。例: `ref: item:iron_ingot`）           |
| `bundle.items[].amount`       | String  | ×  | 1     | 付与数。固定値または範囲（例: `1` / `1~3`）                         |
| `bundle.items[].rate`         | Double  | ×  | 100.0 | 付与確率（0.00 〜 100.00）。省略時は必ず付与                         |
| `bundle.items[].luckAffected` | Boolean | ×  | false | `true` の場合、幸運ステータスによる確率補正を受ける                        |
| `bundle.items[].hidden`       | Boolean | ×  | false | `true` の場合、図鑑等の情報ブック（未実装）にドロップアイテムとして表示されない（秘密のドロップ） |

---

## YAML 例

### 例: LootTable 参照

```yaml
schemaVersion: 1
id: magic_iron_ingot_bundle
category: BUNDLE
name: "&b魔鉄のパケット"
icon: CHEST
rarity: UNCOMMON
lore:
  - "&7魔鉄を詰め込んだ珍しいパケット。"
unTradeable: false
bundle:
  lootTableId:
    ref: loot_table:magic_iron_ingot
  onUse:
    sound: block.anvil.land
    particle: block_break
```

### 例: 個別アイテム指定

```yaml
schemaVersion: 1
id: starter_bundle
category: BUNDLE
name: "&e初心者パック"
icon: CHEST
rarity: COMMON
maxStack: 1
lore:
  - "&7これから冒険を始める人への贈り物。"
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
        ref: item:rare_gem
      amount: 1
      rate: 10.0
      luckAffected: true
      hidden: true
  onUse:
    sound: block.chest.open
    particle: totem_of_undying
```

### 例: LootTable と個別アイテムの併用

```yaml
schemaVersion: 1
id: adventurer_bundle
category: BUNDLE
name: "&6冒険者の荷物"
icon: CHEST
rarity: RARE
maxStack: 1
lore:
  - "&7冒険者が残した荷物。何が入っているか…"
unTradeable: true
bundle:
  lootTableId:
    ref: loot_table:adventurer_common
  items:
    - itemId:
        ref: item:dungeon_medal
      amount: 1
  onUse:
    sound: block.chest.open
```
