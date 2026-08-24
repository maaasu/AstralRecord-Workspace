# Skilltree ノードカタログ

## 役割

この文書は、同じ能力を表すスキルツリーノードの名称・アイコン・タグを統一するためのコンテンツ設計カタログです。AI を含む制作者は、新規ノードを作る前にこの文書と `40_filebase/35.features.skilltree/nodes/*.json` を確認します。

JSON のノード定義は、実際の `effects`、`pointType`、`pointCost`、表示情報の正本です。このカタログは JSON を置き換えず、再利用するノードの判断基準と採用済み表現を示します。

現在のnode JSONでは`lore`を定義しません。省略したノードは、Pluginとエディターでノード固有の説明を表示しません。

## 再利用の判断

`effects[]` の全要素を一組の能力として扱い、次のすべてが一致するときだけ既存の表現を流用します。個別の効果が一つだけ一致しても、ほかの効果が追加・削除・変更されている場合は流用しません。

- 効果種別と対象（例: `status` / `MAX_HEALTH`）
- 補正種別と数値（例: `FLAT` / `10`）
- 使用許可の場合は対象スキル ID

`effects[]` の組合せが変わる場合は別ノードを作ります。プレイヤーへ提示する役割、PP / CP の種別、コスト、職業条件、配置は進行設計上の属性であり、それだけを理由に同じ能力の表示名・アイコン・タグを変えません。似た能力でも、異なる数値または異なる効果の組合せを同じ名前・アイコンで表してプレイヤーを誤認させてはいけません。

## 採用済みの共通表現

| カタログ ID | 効果 | 表示名 | アイコン | タグ |
|---|---|---|---|---|
| `status-starter-foundation` | `MAX_HEALTH / FLAT / 5` + `MAX_MANA / FLAT / 5` + `MAX_ENERGY / FLAT / 5` | `&d旅立ちの記録` | `NETHER_STAR` | `root`, `shared`, `core`, `early` |
| `status-strength-flat-1` | `STRENGTH` / `FLAT` / `1` | `&d紅蓮の星脈` | `BLAZE_POWDER` | `status`, `primary`, `strength`, `ember` |
| `status-dexterity-flat-1` | `DEXTERITY` / `FLAT` / `1` | `&d銀矢の星脈` | `ARROW` | `status`, `primary`, `dexterity` |
| `status-intelligence-flat-1` | `INTELLIGENCE` / `FLAT` / `1` | `&d星詠みの星脈` | `ENCHANTED_BOOK` | `status`, `primary`, `intelligence`, `astral` |
| `status-vitality-flat-1` | `VITALITY` / `FLAT` / `1` | `&d大樹の星脈` | `OAK_SAPLING` | `status`, `primary`, `durability` |
| `status-agility-flat-1` | `AGILITY` / `FLAT` / `1` | `&d風渡りの星脈` | `FEATHER` | `status`, `primary`, `agility`, `wind` |
| `status-luck-flat-1` | `LUCK` / `FLAT` / `1` | `&d巡星の星脈` | `EMERALD` | `status`, `primary`, `luck` |
| `status-attack-flat-1` | `ATTACK` / `FLAT` / `1` | `&d暁刃の星脈` | `IRON_SWORD` | `status`, `offense` |
| `status-defense-flat-1` | `DEFENSE` / `FLAT` / `1` | `&d玄岩の星脈` | `OBSIDIAN` | `status`, `defense`, `durability`, `stone` |
| `status-magic-defense-flat-1` | `MAGIC_DEFENSE` / `FLAT` / `1` | `&d星衣の星脈` | `ENCHANTED_GOLDEN_APPLE` | `status`, `defense`, `astral` |
| `status-max-health-flat-10` | `MAX_HEALTH` / `FLAT` / `10` | `&d灯火の星脈` | `HEART_OF_THE_SEA` | `status`, `resource`, `health`, `starlight` |
| `status-max-mana-flat-10` | `MAX_MANA` / `FLAT` / `10` | `&d蒼泉の星脈` | `AMETHYST_SHARD` | `status`, `resource`, `mana`, `azure` |
| `status-max-energy-flat-5` | `MAX_ENERGY` / `FLAT` / `5` | `&d蒼穹の星脈` | `ENDER_PEARL` | `status`, `resource`, `energy` |
| `status-movement-speed-flat-1` | `MOVEMENT_SPEED` / `FLAT` / `1` | `&d迅風の星脈` | `SUGAR` | `status`, `agility`, `wind` |
| `status-mp-regen-flat-0.2` | `MP_REGEN` / `FLAT` / `0.2` | `&d還流の星脈` | `PRISMARINE_CRYSTALS` | `status`, `resource`, `mana`, `azure` |
| `skill-swordsman-blade-counter` | `skill` / `swordsman_blade_counter` | `&f反照の剣星` | `WHITE_STAINED_GLASS` | `defense` |

表の表示名には JSON に保存する Legacy color code を含めます。同じ能力を追加するときは、表の表示名、`icon`、タグをすべて同一にします。各配置済みノードには、従来どおり一意の `nodeId` を割り当てます。

## 将来skill予約枠の暫定表現

予約枠は能力カタログには含めません。現行schemaで通常非表示のleafを確保するため、全予約枠で次の表示と空効果を共通利用します。

| 項目 | 値 |
|:--|:--|
| `effects` | `[]` |
| 表示名 | `&8未定の星座` |
| アイコン | `GRAY_DYE` |
| タグ | `[]` |
| コスト | 地域に応じた `pointType` / `pointCost: 0` |
| 条件 | 地域に応じた `classId` / `playerLevel: 2147483647` |

`playerLevel: 2147483647` は絶対ロックではなく、専用ロック項目がない現行schema内の暫定表現です。予約枠は後続nodeを持たないleafとし、実skill IDは追加しません。

## カタログの更新規約

- 新しい能力を採用したときは、対応する node JSON と同じ変更でこの表に追加します。
- 既存能力の表示名、アイコン、タグ、コストを変更するときは、カタログの該当行と使用中の node JSON を同時に確認します。
- 同じ能力を職業別ノードへ置く場合も、このカタログの表現を使います。プレイヤーへ提示する役割、職業条件、配置構造の違いだけを理由に別の名称・アイコン・タグを作りません。
- このカタログは地域や座標を管理しません。地域・配置構造の方針は [[35-skilltree]]、最終座標と edge は `structures/*.json` を正本とします。
