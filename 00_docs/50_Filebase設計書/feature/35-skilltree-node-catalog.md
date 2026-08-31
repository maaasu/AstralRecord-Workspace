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
| `skill-adventurer-meditation` | `skill` / `adventurer_meditation` | `&dメディテーション` | `CAMPFIRE` | `root`, `shared`, `core`, `early` |
| `status-strength-flat-1` | `STRENGTH` / `FLAT` / `1` | `&d紅蓮の星脈` | `BLAZE_POWDER` | `status`, `primary`, `strength`, `ember` |
| `status-dexterity-flat-1` | `DEXTERITY` / `FLAT` / `1` | `&d銀矢の星脈` | `ARROW` | `status`, `primary`, `dexterity` |
| `status-intelligence-flat-1` | `INTELLIGENCE` / `FLAT` / `1` | `&d星詠みの星脈` | `ENCHANTED_BOOK` | `status`, `primary`, `intelligence`, `astral` |
| `status-vitality-flat-1` | `VITALITY` / `FLAT` / `1` | `&d大樹の星脈` | `OAK_SAPLING` | `status`, `primary`, `durability` |
| `status-agility-flat-1` | `AGILITY` / `FLAT` / `1` | `&d風渡りの星脈` | `FEATHER` | `status`, `primary`, `agility`, `wind` |
| `status-luck-flat-1` | `LUCK` / `FLAT` / `1` | `&d巡星の星脈` | `EMERALD` | `status`, `primary`, `luck` |
| `status-attack-flat-1` | `ATTACK` / `FLAT` / `1` | `&d暁刃の星脈` | `IRON_SWORD` | `status`, `offense` |
| `status-defense-flat-1` | `DEFENSE` / `FLAT` / `1` | `&d玄岩の星脈` | `OBSIDIAN` | `status`, `defense`, `durability`, `stone` |
| `status-magic-defense-flat-1` | `MAGIC_DEFENSE` / `FLAT` / `1` | `&d星衣の星脈` | `ENCHANTED_GOLDEN_APPLE` | `status`, `defense`, `astral` |
| `status-magic-defense-energy-regen-flat-1` | `MAGIC_DEFENSE` / `FLAT` / `1` + `ENERGY_REGEN` / `FLAT` / `1` | `&d星衣の循環` | `BEACON` | `status`, `defense`, `resource`, `energy`, `astral` |
| `status-max-health-flat-10` | `MAX_HEALTH` / `FLAT` / `10` | `&d灯火の星脈` | `HEART_OF_THE_SEA` | `status`, `resource`, `health`, `starlight` |
| `status-max-mana-flat-10` | `MAX_MANA` / `FLAT` / `10` | `&d蒼泉の星脈` | `AMETHYST_SHARD` | `status`, `resource`, `mana`, `azure` |
| `status-max-energy-flat-5` | `MAX_ENERGY` / `FLAT` / `5` | `&d蒼穹の星脈` | `ENDER_PEARL` | `status`, `resource`, `energy` |
| `status-movement-speed-flat-1` | `MOVEMENT_SPEED` / `FLAT` / `1` | `&d迅風の星脈` | `SUGAR` | `status`, `agility`, `wind` |
| `status-mp-regen-flat-1` | `MP_REGEN` / `FLAT` / `1` | `&d還流の星脈` | `PRISMARINE_CRYSTALS` | `status`, `resource`, `mana`, `azure` |
| `status-max-energy-mp-regen-flat` | `MAX_ENERGY` / `FLAT` / `5` + `MP_REGEN` / `FLAT` / `1` | `&d蒼穹の還流` | `ENDER_PEARL` | `status`, `resource`, `energy`, `mana`, `azure` |
| `status-max-energy-energy-regen-flat` | `MAX_ENERGY` / `FLAT` / `5` + `ENERGY_REGEN` / `FLAT` / `1` | `&d蒼穹の循環` | `ENDER_PEARL` | `status`, `resource`, `energy`, `wind` |
| `status-max-energy-dual-regen-flat` | `MAX_ENERGY` / `FLAT` / `5` + `MP_REGEN` / `FLAT` / `1` + `ENERGY_REGEN` / `FLAT` / `1` | `&d双環の星脈` | `END_CRYSTAL` | `status`, `resource`, `energy`, `mana`, `azure` |
| `status-max-mana-mp-regen-flat` | `MAX_MANA` / `FLAT` / `6` + `MP_REGEN` / `FLAT` / `1` | `&d蒼泉の還流` | `AMETHYST_SHARD` | `status`, `resource`, `mana`, `azure` |
| `skill-administrator-shield-recharge` | `skill` / `administrator_shield_recharge` | `&bシールドリチャージ` | `SHIELD` | `defense` |
| `skill-swordsman-last-shield` | `skill` / `swordsman_last_shield` | `&bラストシールド` | `BEACON` | `defense` |
| `skill-swordsman-flame-rush` | `skill` / `swordsman_flame_rush` | `&6フレイムラッシュ` | `CRIMSON_ROOTS` | `fire` |
| `skill-swordsman-bastion-strike` | `skill` / `swordsman_bastion_strike` | `&bバスティオンストライク` | `SOUL_CAMPFIRE` | `defense` |
| `skill-hunter-crash-arrow` | `skill` / `hunter_crash_arrow` | `&bクラッシュアロー` | `TARGET` | `offense` |
| `skill-hunter-heal-arrow` | `skill` / `hunter_heal_arrow` | `&aヒールアロー` | `GLOW_BERRIES` | `light` |
| `skill-hunter-spell-step` | `skill` / `hunter_spell_step` | `&eスペルステップ` | `ENDER_PEARL` | `agility`, `wind` |
| `skill-mage-arcane-flow` | `skill` / `mage_arcane_flow` | `&dアーケインフロー` | `ENCHANTED_BOOK` | `core`, `mana`, `astral` |
| `skill-mage-sparking` | `skill` / `mage_sparking` | `&eスパーキング` | `GLOWSTONE_DUST` | `lightning`, `shocked` |
| `skill-mage-frost-blizzard` | `skill` / `mage_frost_blizzard` | `&bフロストブリザード` | `DIAMOND_NAUTILUS_ARMOR` | `ice`, `azure` |
| `skill-mage-frost-ball` | `skill` / `mage_frost_ball` | `&bフロストボール` | `SNOWBALL` | `ice`, `azure` |
| `skill-administrator-just-dodge` | `skill` / `administrator_just_dodge` | `&eジャスト回避` | `RABBIT_FOOT` | `defense`, `agility`, `wind` |

表の表示名には JSON に保存する Legacy color code を含めます。同じ能力を追加するときは、表の表示名、`icon`、タグをすべて同一にします。各配置済みノードには、従来どおり一意の `nodeId` を割り当てます。

## PPステータスパッケージ

PPノードは、1PPあたりの選択価値を確保するため、次の複数statusを一組として定義します。表の効果はすべて `FLAT` です。同じパッケージを複数の配置へ置く場合は、表示名・アイコン・タグを完全に一致させます。

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|---|---|---|---|---|---|
| `status-pp-foundation-attributes` | `1048` | `STRENGTH / DEXTERITY / INTELLIGENCE / VITALITY / AGILITY / LUCK` を各 `FLAT / 1` | `&d六環の起点` | `NETHER_STAR` | `status`, `primary`, `strength`, `dexterity`, `intelligence`, `durability`, `agility`, `luck` |
| `status-pp-foundation-offense` | `1049` | `ATTACK / FLAT / 4` + `STRENGTH / DEXTERITY / INTELLIGENCE` を各 `FLAT / 1` | `&d闘志の起点` | `IRON_SWORD` | `status`, `offense`, `primary`, `strength`, `dexterity`, `intelligence`, `ember` |
| `status-pp-foundation-defense` | `1050` | `VITALITY / FLAT / 2` + `DEFENSE / FLAT / 3` + `MAGIC_DEFENSE / FLAT / 3` | `&d守護の起点` | `SHIELD` | `status`, `primary`, `defense`, `durability`, `astral` |
| `status-pp-foundation-life-mana` | `1052` | `INTELLIGENCE / FLAT / 2` + `VITALITY / FLAT / 2` + `MAX_HEALTH / FLAT / 44` + `MAX_MANA / FLAT / 34` + `MP_REGEN / FLAT / 1` + `HP_REGEN / FLAT / 1` | `&d命脈の起点` | `HEART_OF_THE_SEA` | `status`, `primary`, `resource`, `intelligence`, `mana`, `health`, `durability`, `azure` |
| `status-pp-foundation-resources` | `1054` | `MAX_HEALTH / FLAT / 19` + `MAX_MANA / FLAT / 29` + `MAX_ENERGY / FLAT / 5` + `HP_REGEN / FLAT / 1` + `MP_REGEN / FLAT / 1` + `ENERGY_REGEN / FLAT / 1` | `&d循環の起点` | `END_CRYSTAL` | `status`, `resource`, `health`, `mana`, `energy`, `azure` |
| `status-pp-foundation-energy-mobility` | `1055` | `AGILITY / FLAT / 4` + `MAX_ENERGY / FLAT / 20` + `ENERGY_REGEN / FLAT / 1` + `ATTACK_SPEED / FLAT / 2` + `MOVEMENT_SPEED / FLAT / 2` | `&d活風の起点` | `ENDER_PEARL` | `status`, `primary`, `resource`, `energy`, `agility`, `wind` |
| `status-pp-north-ember` | `1056`, `1060`, `1064`, `1068` | `ATTACK / FLAT / 3` + `STRENGTH / FLAT / 2` | `&d紅蓮の連星` | `BLAZE_POWDER` | `status`, `offense`, `primary`, `strength`, `ember` |
| `status-pp-combat-major` | `1072`, `1076` | `ATTACK / FLAT / 5` + `STRENGTH / DEXTERITY / INTELLIGENCE` を各 `FLAT / 2` | `&d闘志の連星` | `FIRE_CHARGE` | `status`, `offense`, `primary`, `strength`, `dexterity`, `intelligence`, `ember` |
| `status-pp-offense-notable` | `1084` | `ATTACK / FLAT / 10` + `STRENGTH / DEXTERITY / INTELLIGENCE` を各 `FLAT / 4` | `&d征戦の極星` | `NETHERITE_SWORD` | `status`, `offense`, `primary`, `strength`, `dexterity`, `intelligence`, `ember` |
| `status-pp-east-arrow` | `1057`, `1061`, `1065`, `1069` | `DEXTERITY / FLAT / 3` + `AGILITY / FLAT / 2` | `&d銀矢の連星` | `ARROW` | `status`, `primary`, `dexterity`, `agility`, `wind` |
| `status-pp-energy-major` | `1073`, `1077` | `AGILITY / FLAT / 3` + `MAX_ENERGY / FLAT / 10` + `ENERGY_REGEN / FLAT / 1` | `&d蒼穹の連星` | `ENDER_PEARL` | `status`, `primary`, `resource`, `energy`, `agility`, `wind` |
| `status-pp-mobility-notable` | `1081` | `AGILITY / FLAT / 6` + `MAX_ENERGY / FLAT / 20` + `ENERGY_REGEN / FLAT / 2` + `ATTACK / FLAT / 4` | `&d疾駆の極星` | `ELYTRA` | `status`, `primary`, `resource`, `energy`, `agility`, `wind` |
| `status-pp-south-grove` | `1058`, `1062`, `1066`, `1070` | `VITALITY / FLAT / 3` + `MAX_HEALTH / FLAT / 25` | `&d大樹の連星` | `OAK_SAPLING` | `status`, `primary`, `resource`, `health`, `durability` |
| `status-pp-south-stone` | `1074`, `1078` | `VITALITY / FLAT / 3` + `DEFENSE / FLAT / 3` + `MAX_HEALTH / FLAT / 20` | `&d玄岩の連星` | `OBSIDIAN` | `status`, `primary`, `durability`, `defense`, `health`, `stone` |
| `status-pp-defense-notable` | `1082` | `VITALITY / FLAT / 8` + `MAX_HEALTH / FLAT / 75` + `DEFENSE / FLAT / 7` + `MAGIC_DEFENSE / FLAT / 7` + `HP_REGEN / FLAT / 1` | `&d不壊の極星` | `ENCHANTED_GOLDEN_APPLE` | `status`, `primary`, `durability`, `defense`, `health`, `stone` |
| `status-pp-west-astral` | `1059`, `1063`, `1067`, `1071` | `INTELLIGENCE / FLAT / 3` + `MAX_MANA / FLAT / 25` | `&d星詠みの連星` | `ENCHANTED_BOOK` | `status`, `primary`, `intelligence`, `resource`, `mana`, `astral` |
| `status-pp-west-azure` | `1075`, `1079` | `INTELLIGENCE / FLAT / 4` + `MAX_MANA / FLAT / 29` + `MP_REGEN / FLAT / 1` | `&d蒼泉の連星` | `AMETHYST_SHARD` | `status`, `primary`, `intelligence`, `resource`, `mana`, `azure` |
| `status-pp-resource-notable` | `1083` | `MAX_HEALTH / FLAT / 40` + `MAX_MANA / FLAT / 60` + `MAX_ENERGY / FLAT / 20` + `HP_REGEN / FLAT / 0.5` + `MP_REGEN / FLAT / 0.8` + `ENERGY_REGEN / FLAT / 2` | `&d循環の極星` | `END_CRYSTAL` | `status`, `resource`, `health`, `mana`, `energy`, `azure` |

PPの通常・強化パッケージは1PP、各方向のnotableは2PPとします。通常・強化・notableに `playerLevel` 条件は設定せず、接続経路とPP残高で進行を制御します。ノード固有の `lore` は定義しません。

## ソードマン専門円環

ソードマン専門円環は、汎用基礎幹から分岐する1CPの小nodeと、2CPのnotableで構成します。同じ効果の小nodeは表示名・アイコン・タグを完全に共通化します。

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|---|---|---|---|---|---|
| `status-swordsman-critical-ring` | `1215`～`1218` | `CRITICAL_RATE / FLAT / 0.5` | `&d会心の星環` | `QUARTZ` | `status`, `offense` |
| `status-swordsman-critical-notable` | `1219` | `CRITICAL_RATE / FLAT / 3` + `CRITICAL_DAMAGE / FLAT / 10` | `&6会心剣の極星` | `NETHERITE_SWORD` | `status`, `offense` |
| `status-swordsman-super-critical-ring` | `1220`～`1223` | `SUPER_CRITICAL_RATE / FLAT / 0.5` | `&d超星の星環` | `AMETHYST_SHARD` | `status`, `offense`, `astral` |
| `status-swordsman-super-critical-notable` | `1224` | `SUPER_CRITICAL_RATE / FLAT / 2` + `SUPER_CRITICAL_DAMAGE / FLAT / 10` | `&6超星剣の極星` | `NETHER_STAR` | `status`, `offense`, `astral` |
| `status-swordsman-max-shield-ring` | `1225`～`1228` | `MAX_SHIELD / FLAT / 2` | `&b堅盾の星環` | `IRON_INGOT` | `status`, `defense`, `shield` |
| `status-swordsman-max-shield-notable` | `1229` | `MAX_SHIELD / FLAT / 12` + `DEFENSE / FLAT / 2` | `&6不落の極星` | `SHIELD` | `status`, `defense`, `shield`, `durability` |
| `status-swordsman-shield-recharge-ring` | `1230`～`1233` | `SHIELD_RECHARGE_REDUCTION / FLAT / 2.5` | `&b再生障壁の星環` | `PRISMARINE_CRYSTALS` | `status`, `defense`, `shield`, `resource` |
| `status-swordsman-shield-recharge-notable` | `1234` | `SHIELD_RECHARGE_REDUCTION / FLAT / 10` + `MAX_SHIELD / FLAT / 5` | `&6瞬復城塞の極星` | `RECOVERY_COMPASS` | `status`, `defense`, `shield`, `resource` |

4円環を全取得した場合は、`CRITICAL_RATE +5`、`CRITICAL_DAMAGE +10`、`SUPER_CRITICAL_RATE +4`、`SUPER_CRITICAL_DAMAGE +10`、`MAX_SHIELD +25`、`DEFENSE +2`、`SHIELD_RECHARGE_REDUCTION +20` となります。率と短縮の `FLAT` 値はパーセントポイントです。超星会心は主撃に加えて7個の追尾弾を生成するため、通常会心より遠い専門選択として扱い、最終値はプレイテスト対象とします。

## ハンター専門円環

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|---|---|---|---|---|---|
| `status-ranged-attack-flat-1` | `1236`, `1239`, `1244`, `1248`, `1251`, `1255`, `1260`, `1262`～`1265` | `RANGED_ATTACK / FLAT / 1` | `&d遠矢の星脈` | `BOW` | `status`, `offense`, `wind` |
| `status-accuracy-flat-1` | `1238`, `1246`, `1254` | `ACCURACY / FLAT / 1` | `&d照準の星脈` | `SPYGLASS` | `status`, `offense`, `accuracy` |
| `status-ranged-accuracy-flat-1` | `1241` | `RANGED_ATTACK / FLAT / 1` + `ACCURACY / FLAT / 1` | `&d狙撃の連星` | `CROSSBOW` | `status`, `offense`, `accuracy`, `wind` |
| `status-hunter-ranged-notable` | `1266` | `RANGED_ATTACK / FLAT / 5` + `ACCURACY / FLAT / 3` | `&6天穹射の極星` | `CROSSBOW` | `status`, `offense`, `accuracy`, `wind` |
| `status-hunter-shield-break-ring` | `1267`～`1270` | `SHIELD_BREAK / FLAT / 0.5` | `&b砕盾の星環` | `PRISMARINE_CRYSTALS` | `status`, `offense`, `shield` |
| `status-hunter-shield-break-notable` | `1271` | `SHIELD_BREAK / FLAT / 3` + `RANGED_DEFENSE_PENETRATION_RATE / FLAT / 5` | `&6砕盾の極星` | `SPECTRAL_ARROW` | `status`, `offense`, `shield` |
| `status-hunter-movement-notable` | `1276` | `MOVEMENT_SPEED / FLAT / 5` + `EVASION / FLAT / 2` | `&6風歩の極星` | `ELYTRA` | `status`, `agility`, `defense`, `wind` |
| `status-hunter-energy-cost-ring` | `1277`～`1280` | `ENERGY_COST_REDUCTION / FLAT / 1` | `&b節気の星環` | `HONEY_BOTTLE` | `status`, `resource`, `energy`, `azure` |
| `status-hunter-energy-notable` | `1281` | `ENERGY_COST_REDUCTION / FLAT / 4` + `ENERGY_REGEN / FLAT / 2` | `&6蒼穹の極星` | `NETHER_STAR` | `status`, `resource`, `energy`, `azure` |

4円環を含むハンター地域の全取得は54ハンターCPです。専門円環の合計は `RANGED_ATTACK +9`、`ACCURACY +3`、`SHIELD_BREAK +5`、`RANGED_DEFENSE_PENETRATION_RATE +5`、`MOVEMENT_SPEED +9`、`EVASION +2`、`ENERGY_COST_REDUCTION +8`、`ENERGY_REGEN +2` です。skill解放3nodeは関連する小nodeから独立分岐します。

## メイジ専門円環

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|---|---|---|---|---|---|
| `status-magic-attack-flat-1` | `1301`, `1303`, `1305`, `1312`, `1321`, `1327`～`1330` | `MAGIC_ATTACK / FLAT / 1` | `&d魔導の星脈` | `BLAZE_ROD` | `status`, `offense`, `astral` |
| `status-mana-cost-reduction-flat-2-5` | `1313` | `MANA_COST_REDUCTION / FLAT / 2.5` | `&d節魔の星脈` | `LAPIS_LAZULI` | `status`, `resource`, `mana`, `azure` |
| `status-cast-time-reduction-flat-2-5` | `1325` | `CAST_TIME_REDUCTION / FLAT / 2.5` | `&d詠唱の星脈` | `CLOCK` | `status`, `mana`, `astral` |
| `status-mage-arcane-notable` | `1331` | `MAGIC_ATTACK / FLAT / 4` + `MAGIC_DEFENSE_PENETRATION_RATE / FLAT / 6` | `&6大魔導の極星` | `ENCHANTED_BOOK` | `status`, `offense`, `astral` |
| `status-mage-fire-ring` | `1332`～`1335` | `FIRE_DAMAGE_INCREASE / FLAT / 2` | `&c紅炎の星環` | `FIRE_CHARGE` | `status`, `offense`, `fire`, `ember` |
| `status-mage-fire-notable` | `1336` | `FIRE_DAMAGE_INCREASE / FLAT / 8` + `FIRE_PENETRATION / FLAT / 6` | `&6灼炎の極星` | `MAGMA_CREAM` | `status`, `offense`, `fire`, `ember` |
| `status-mage-lightning-ring` | `1337`～`1340` | `LIGHTNING_DAMAGE_INCREASE / FLAT / 2` | `&e雷鳴の星環` | `LIGHTNING_ROD` | `status`, `offense`, `lightning`, `shocked` |
| `status-mage-lightning-notable` | `1341` | `LIGHTNING_DAMAGE_INCREASE / FLAT / 8` + `SHOCKED_APPLY_CHANCE / FLAT / 20` | `&6轟雷の極星` | `LIGHTNING_ROD` | `status`, `offense`, `lightning`, `shocked` |
| `status-mage-ice-ring` | `1342`～`1345` | `ICE_DAMAGE_INCREASE / FLAT / 2` | `&b氷紋の星環` | `BLUE_ICE` | `status`, `offense`, `ice`, `azure` |
| `status-mage-ice-notable` | `1346` | `ICE_DAMAGE_INCREASE / FLAT / 8` + `COOLDOWN_REDUCTION / FLAT / 6` | `&6白嵐の極星` | `DIAMOND_NAUTILUS_ARMOR` | `status`, `offense`, `ice`, `azure` |

4円環を含むメイジ地域の全取得は55メイジCPです。全取得時は `MAGIC_ATTACK +13`、`MAGIC_DEFENSE_PENETRATION_RATE +6`、火・雷・氷の各ダメージ増加 `+16`、`FIRE_PENETRATION +6`、`SHOCKED_APPLY_CHANCE +20`、`COOLDOWN_REDUCTION +6` を得ます。skill解放4nodeは関連する小nodeから独立分岐します。

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
