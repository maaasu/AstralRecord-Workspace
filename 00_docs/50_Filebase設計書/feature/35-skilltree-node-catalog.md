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
| `status-starter-foundation` | `MAX_HEALTH / FLAT / 10` + `MAX_MANA / FLAT / 10` + `MAX_ENERGY / FLAT / 10` | `&d旅立ちの記録` | `NETHER_STAR` | `root`, `shared`, `core`, `early` |
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

## PPステータスパッケージ

PPノードは、1PPあたりの選択価値を確保するため、次の複数statusを一組として定義します。表の効果はすべて `FLAT` です。同じパッケージを複数の配置へ置く場合は、表示名・アイコン・タグを完全に一致させます。

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|---|---|---|---|---|---|
| `status-pp-foundation-offense` | `1048` | `ATTACK / FLAT / 2` + `STRENGTH / FLAT / 2` | `&d刃の起点` | `IRON_SWORD` | `status`, `offense`, `primary`, `strength`, `ember` |
| `status-pp-foundation-brutality` | `1049` | `ATTACK / FLAT / 2` + `VITALITY / FLAT / 1` | `&d剛刃の起点` | `SHIELD` | `status`, `offense`, `primary`, `durability`, `ember` |
| `status-pp-foundation-precision` | `1050` | `DEXTERITY / FLAT / 2` + `AGILITY / FLAT / 2` | `&d銀矢の起点` | `ARROW` | `status`, `primary`, `dexterity`, `agility`, `wind` |
| `status-pp-foundation-swiftness` | `1051` | `AGILITY / FLAT / 2` + `ACCURACY / FLAT / 1` | `&d疾走の起点` | `FEATHER` | `status`, `offense`, `agility`, `accuracy`, `wind` |
| `status-pp-foundation-vigor` | `1052` | `MAX_HEALTH / FLAT / 25` + `VITALITY / FLAT / 2` | `&d灯火の起点` | `HEART_OF_THE_SEA` | `status`, `primary`, `resource`, `health`, `durability` |
| `status-pp-foundation-guard` | `1053` | `DEFENSE / FLAT / 2` + `MAX_HEALTH / FLAT / 15` | `&d玄岩の起点` | `OBSIDIAN` | `status`, `defense`, `durability`, `health`, `stone` |
| `status-pp-foundation-arcana` | `1054` | `INTELLIGENCE / FLAT / 2` + `MAX_MANA / FLAT / 20` | `&d星詠みの起点` | `ENCHANTED_BOOK` | `status`, `primary`, `resource`, `intelligence`, `mana`, `astral` |
| `status-pp-foundation-flow` | `1055` | `INTELLIGENCE / FLAT / 2` + `MAX_MANA / FLAT / 10` + `MP_REGEN / FLAT / 0.2` | `&d還流の起点` | `AMETHYST_SHARD` | `status`, `primary`, `resource`, `intelligence`, `mana`, `azure` |
| `status-pp-north-ember` | `1056`, `1060`, `1064`, `1068` | `ATTACK / FLAT / 3` + `STRENGTH / FLAT / 2` | `&d紅蓮の連星` | `BLAZE_POWDER` | `status`, `offense`, `primary`, `strength`, `ember` |
| `status-pp-north-war` | `1072`, `1076`, `1080` | `ATTACK / FLAT / 4` + `STRENGTH / FLAT / 3` + `CRITICAL_RATE / FLAT / 0.5` | `&d破軍の連星` | `FIRE_CHARGE` | `status`, `offense`, `primary`, `strength`, `luck`, `ember` |
| `status-pp-north-capstone` | `1084` | `ATTACK / FLAT / 6` + `STRENGTH / FLAT / 5` + `CRITICAL_RATE / FLAT / 1` + `CRITICAL_DAMAGE / FLAT / 5` | `&d紅蓮の極星` | `NETHERITE_SWORD` | `status`, `offense`, `primary`, `strength`, `luck`, `ember` |
| `status-pp-east-arrow` | `1057`, `1061`, `1065`, `1069` | `DEXTERITY / FLAT / 3` + `AGILITY / FLAT / 2` | `&d銀矢の連星` | `ARROW` | `status`, `primary`, `dexterity`, `agility`, `wind` |
| `status-pp-east-wind` | `1073`, `1077` | `DEXTERITY / FLAT / 4` + `AGILITY / FLAT / 3` + `ACCURACY / FLAT / 1` | `&d疾風の連星` | `FEATHER` | `status`, `offense`, `primary`, `dexterity`, `agility`, `accuracy`, `wind` |
| `status-pp-east-capstone` | `1081` | `DEXTERITY / FLAT / 6` + `AGILITY / FLAT / 5` + `ACCURACY / FLAT / 2` + `EVASION / FLAT / 1` | `&d天翔の極星` | `PHANTOM_MEMBRANE` | `status`, `offense`, `primary`, `dexterity`, `agility`, `accuracy`, `wind` |
| `status-pp-south-grove` | `1058`, `1062`, `1066`, `1070` | `VITALITY / FLAT / 3` + `MAX_HEALTH / FLAT / 25` | `&d大樹の連星` | `OAK_SAPLING` | `status`, `primary`, `resource`, `health`, `durability` |
| `status-pp-south-stone` | `1074`, `1078` | `VITALITY / FLAT / 3` + `DEFENSE / FLAT / 3` + `MAX_HEALTH / FLAT / 20` | `&d玄岩の連星` | `OBSIDIAN` | `status`, `primary`, `durability`, `defense`, `health`, `stone` |
| `status-pp-south-capstone` | `1082` | `VITALITY / FLAT / 6` + `MAX_HEALTH / FLAT / 50` + `DEFENSE / FLAT / 5` + `MAGIC_DEFENSE / FLAT / 3` | `&d不壊の極星` | `ENCHANTED_GOLDEN_APPLE` | `status`, `primary`, `durability`, `defense`, `health`, `stone` |
| `status-pp-west-astral` | `1059`, `1063`, `1067`, `1071` | `INTELLIGENCE / FLAT / 3` + `MAX_MANA / FLAT / 25` | `&d星詠みの連星` | `ENCHANTED_BOOK` | `status`, `primary`, `intelligence`, `resource`, `mana`, `astral` |
| `status-pp-west-azure` | `1075`, `1079` | `INTELLIGENCE / FLAT / 4` + `MAX_MANA / FLAT / 30` + `MP_REGEN / FLAT / 0.2` | `&d蒼泉の連星` | `AMETHYST_SHARD` | `status`, `primary`, `intelligence`, `resource`, `mana`, `azure` |
| `status-pp-west-capstone` | `1083` | `INTELLIGENCE / FLAT / 6` + `MAX_MANA / FLAT / 60` + `MP_REGEN / FLAT / 0.5` + `MAX_ENERGY / FLAT / 10` | `&d天穹の極星` | `END_CRYSTAL` | `status`, `primary`, `intelligence`, `resource`, `mana`, `energy`, `azure` |

PPの通常パッケージはレベル条件なし、各方向のcapstoneだけは強力な複合statusとして `playerLevel` 条件を持ちます。ノード固有の `lore` は定義しません。

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
