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
| `skill-swordsman-shield-drain` | `skill` / `swordsman_shield_drain` | `&bシールドドレイン` | `TUBE_CORAL` | `offense` |
| `skill-swordsman-flame-rush` | `skill` / `swordsman_flame_rush` | `&6フレイムラッシュ` | `CRIMSON_ROOTS` | `fire` |
| `skill-swordsman-bastion-strike` | `skill` / `swordsman_bastion_strike` | `&bバスティオンストライク` | `SOUL_CAMPFIRE` | `defense` |
| `skill-swordsman-exept-stamp` | `skill` / `swordsman_exept_stamp` | `&6エクゼプトスタンプ` | `ANVIL` | `offense`, `status` |
| `skill-hunter-crash-arrow` | `skill` / `hunter_crash_arrow` | `&bクラッシュアロー` | `TARGET` | `offense` |
| `skill-hunter-heal-arrow` | `skill` / `hunter_heal_arrow` | `&aヒールアロー` | `GLOW_BERRIES` | `light` |
| `skill-sharpshooter-heal-arrow-alpha` | `skill` / `archer_heal_arrow_alpha` | `&aヒールアローα` | `GLOW_BERRIES` | `light` |
| `skill-sharpshooter-spreading-ambition` | `skill` / `sharpshooter_spreading_ambition` | `&a拡散する野望` | `TORCHFLOWER_SEEDS` | `offense` |
| `skill-sharpshooter-phantom-shot` | `skill` / `sharpshooter_phantom_shot` | `&5ファントムショット` | `GLOW_INK_SAC` | `offense`, `dark`, `astral` |
| `skill-phantom-archer-heal-arrow-alpha` | `skill` / `archer_heal_arrow_alpha` | `&aヒールアローα` | `GLOW_BERRIES` | `light` |
| `skill-phantom-archer-phantom-shot` | `skill` / `sharpshooter_phantom_shot` | `&5ファントムショット` | `GLOW_INK_SAC` | `offense`, `dark`, `astral` |
| `skill-hunter-spell-step` | `skill` / `hunter_spell_step` | `&eスペルステップ` | `ENDER_PEARL` | `agility`, `wind` |
| `skill-hunter-build-up` | `skill` / `hunter_build_up` | `&eビルドアップ` | `TIPPED_ARROW` | `offense`, `wind` |
| `skill-mage-arcane-flow` | `skill` / `mage_arcane_flow` | `&dアーケインフロー` | `ENCHANTED_BOOK` | `core`, `mana`, `astral` |
| `skill-mage-sparking` | `skill` / `mage_sparking` | `&eスパーキング` | `GLOWSTONE_DUST` | `lightning`, `shocked` |
| `skill-mage-fireball` | `skill` / `mage_fireball` | `&6ファイアーボール` | `FIRE_CHARGE` | `fire`, `ember` |
| `skill-mage-frost-blizzard` | `skill` / `mage_frost_blizzard` | `&bフロストブリザード` | `DIAMOND_NAUTILUS_ARMOR` | `ice`, `azure` |
| `skill-mage-frost-ball` | `skill` / `mage_frost_ball` | `&bフロストボール` | `SNOWBALL` | `ice`, `azure` |
| `skill-administrator-just-dodge` | `skill` / `administrator_just_dodge` | `&eジャスト回避` | `RABBIT_FOOT` | `defense`, `agility`, `wind` |

表の表示名には JSON に保存する Legacy color code を含めます。同じ能力を追加するときは、表の表示名、`icon`、タグをすべて同一にします。`administrator_just_dodge` は冒険者 `1350` とハンター `1355` で同じ表現を再利用します。各配置済みノードには、従来どおり一意の `nodeId` を割り当てます。

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
| `status-swordsman-shield-break-speed-ring` | `1356`～`1359` | `SHIELD_BREAK / FLAT / 1` + `ATTACK_SPEED / FLAT / 1` | `&b破盾疾走の星環` | `PRISMARINE_CRYSTALS` | `status`, `offense`, `shield`, `agility` |
| `status-swordsman-shield-break-speed-notable` | `1360` | `SHIELD_BREAK / FLAT / 4` + `ATTACK_SPEED / FLAT / 4` | `&6破盾迅刃の極星` | `NETHERITE_SWORD` | `status`, `offense`, `shield`, `agility` |

5円環を全取得した場合は、`CRITICAL_RATE +5`、`CRITICAL_DAMAGE +10`、`SUPER_CRITICAL_RATE +4`、`SUPER_CRITICAL_DAMAGE +10`、`MAX_SHIELD +25`、`DEFENSE +2`、`SHIELD_RECHARGE_REDUCTION +20`、`SHIELD_BREAK +8`、`ATTACK_SPEED +8` となります。率と短縮の `FLAT` 値はパーセントポイントです。超星会心は主撃に加えて7個の追尾弾を生成するため、通常会心より遠い専門選択として扱い、最終値はプレイテスト対象とします。

## ハンター専門円環

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|---|---|---|---|---|---|
| `status-ranged-attack-flat-1` | `1236`, `1239`, `1244`, `1248`, `1251`, `1255`, `1260`, `1262`～`1265` | `RANGED_ATTACK / FLAT / 1` | `&d遠矢の星脈` | `BOW` | `status`, `offense`, `wind` |
| `status-accuracy-flat-1` | `1238`, `1246`, `1254` | `ACCURACY / FLAT / 1` | `&d照準の星脈` | `SPYGLASS` | `status`, `offense`, `accuracy` |
| `status-ranged-accuracy-flat-1` | `1241` | `RANGED_ATTACK / FLAT / 1` + `ACCURACY / FLAT / 1` | `&d狙撃の連星` | `CROSSBOW` | `status`, `offense`, `accuracy`, `wind` |
| `status-hunter-ranged-notable` | `1266` | `RANGED_ATTACK / FLAT / 5` + `ACCURACY / FLAT / 3` | `&6天穹射の極星` | `CROSSBOW` | `status`, `offense`, `accuracy`, `wind` |
| `status-hunter-shield-break-ring` | `1267`～`1270` | `SHIELD_BREAK / FLAT / 0.25` | `&b砕盾の星環` | `PRISMARINE_CRYSTALS` | `status`, `offense`, `shield` |
| `status-hunter-shield-break-notable` | `1271` | `SHIELD_BREAK / FLAT / 1` + `RANGED_DEFENSE_PENETRATION_RATE / FLAT / 5` | `&6砕盾の極星` | `SPECTRAL_ARROW` | `status`, `offense`, `shield` |
| `status-hunter-movement-notable` | `1276` | `MOVEMENT_SPEED / FLAT / 5` + `EVASION / FLAT / 2` | `&6風歩の極星` | `ELYTRA` | `status`, `agility`, `defense`, `wind` |
| `status-hunter-energy-cost-ring` | `1277`～`1280` | `ENERGY_COST_REDUCTION / FLAT / 1` | `&b節気の星環` | `HONEY_BOTTLE` | `status`, `resource`, `energy`, `azure` |
| `status-hunter-energy-notable` | `1281` | `ENERGY_COST_REDUCTION / FLAT / 4` + `ENERGY_REGEN / FLAT / 2` | `&6蒼穹の極星` | `NETHER_STAR` | `status`, `resource`, `energy`, `azure` |

4円環を含むハンター地域の全取得は55ハンターCPです。専門円環の合計は `RANGED_ATTACK +9`、`ACCURACY +3`、`SHIELD_BREAK +2`、`RANGED_DEFENSE_PENETRATION_RATE +5`、`MOVEMENT_SPEED +9`、`EVASION +2`、`ENERGY_COST_REDUCTION +8`、`ENERGY_REGEN +2` です。skill解放4nodeは関連する小nodeから独立分岐します。

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

## ウィザード専門枝

ウィザードの配置領域は共通魔導24node、エレメンタル48node、アーケイン48nodeのstatus nodeだけで構成する。全配置nodeは1CPで、`unlockCondition.classId: wizard`を持つ。メテオの使用許可node `2260`、セルフヒールの使用許可node `2261`、エレメンタルプリズムの使用許可node `2262`、エレメンタルボールの使用許可node `2263`、プリズムコンディションの使用許可node `2264`、バーンメテオストライクの使用許可node `2265`、イミュレートスパークの使用許可node `2266` は同じ条件で定義するが、現行の構造には配置しない。

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `skill-wizard-meteor` | `2260` | `skill` / `wizard_meteor` | `&cメテオ` | `GILDED_BLACKSTONE` | `offense`, `fire`, `ember` |
| `skill-wizard-self-heal` | `2261` | `skill` / `wizard_self_heal` | `&dセルフヒール` | `APPLE` | `defense` |
| `skill-wizard-elemental-prism` | `2262` | `skill` / `wizard_elemental_prism` | `&dエレメンタルプリズム` | `END_CRYSTAL` | `offense`, `element`, `fire`, `ice`, `lightning` |
| `skill-wizard-elemental-ball` | `2263` | `skill` / `wizard_elemental_ball` | `&dエレメンタルボール` | `WHITE_GLAZED_TERRACOTTA` | `offense`, `element` |
| `skill-wizard-prism-condition` | `2264` | `skill` / `wizard_prism_condition` | `&dプリズムコンディション` | `MAGENTA_CARPET` | `resource`, `mana`, `astral` |
| `skill-wizard-burn-meteor-strike` | `2265` | `skill` / `wizard_burn_meteor_strike` | `&cバーンメテオストライク` | `MAGMA_BLOCK` | `offense`, `fire`, `ember` |
| `skill-wizard-emulate-spark` | `2266` | `skill` / `wizard_emulate_spark` | `&eイミュレートスパーク` | `WHEAT` | `offense`, `lightning`, `shocked` |

### 共通魔導パッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-wizard-common-path` | `1535`, `1536`, `1543`, `1544`, `1551`, `1552` | MAGIC_ATTACK / INTELLIGENCE `SCALAR +0.003`、MAX_MANA `FLAT +5` | `&d秘奥の星路` | `BLAZE_ROD` | `status`, `offense`, `resource`, `mana`, `astral` |
| `status-wizard-common-magic` | `1537`〜`1541` | MAGIC_ATTACK `SCALAR +0.01` | `&d魔導の星環` | `AMETHYST_SHARD` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-common-magic-notable` | `1542` | MAGIC_ATTACK `SCALAR +0.03`、MAGIC_DEFENSE_PENETRATION_RATE `FLAT +3` | `&6大魔導の極星` | `ENCHANTING_TABLE` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-common-intelligence` | `1545`〜`1549` | INTELLIGENCE `SCALAR +0.01` | `&d叡智の星環` | `BOOK` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-common-intelligence-notable` | `1550` | INTELLIGENCE `SCALAR +0.03`、CAST_TIME_REDUCTION `FLAT +3` | `&6星界叡智の極星` | `KNOWLEDGE_BOOK` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-common-mana` | `1553`〜`1557` | MAX_MANA `FLAT +12` | `&b蒼泉の星環` | `LAPIS_LAZULI` | `status`, `resource`, `mana`, `azure` |
| `status-wizard-common-mana-notable` | `1558` | MAX_MANA `FLAT +35`、MP_REGEN `FLAT +1.5` | `&6深蒼泉の極星` | `CONDUIT` | `status`, `resource`, `mana`, `azure`, `astral` |

### エレメンタルパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-wizard-elemental-path` | `1559`〜`1570` | FIRE / ICE / LIGHTNING_DAMAGE_INCREASE `FLAT +0.5` | `&d三相の星路` | `PRISMARINE_SHARD` | `status`, `offense`, `element`, `fire`, `ice`, `lightning` |
| `status-wizard-elemental-fire` | `1571`〜`1575` | FIRE_DAMAGE_INCREASE `FLAT +2` | `&c紅蓮の星環` | `BLAZE_POWDER` | `status`, `offense`, `element`, `fire` |
| `status-wizard-elemental-fire-notable` | `1576` | FIRE_DAMAGE_INCREASE `FLAT +6` | `&6灼天の極星` | `BLAZE_ROD` | `status`, `offense`, `element`, `fire` |
| `status-wizard-elemental-fire-penetration` | `1577`〜`1581` | FIRE_PENETRATION `FLAT +1` | `&c灼穿の星環` | `MAGMA_CREAM` | `status`, `offense`, `element`, `fire` |
| `status-wizard-elemental-fire-penetration-notable` | `1582` | FIRE_PENETRATION `FLAT +4` | `&6炎界穿孔の極星` | `FIRE_CHARGE` | `status`, `offense`, `element`, `fire` |
| `status-wizard-elemental-ice` | `1583`〜`1587` | ICE_DAMAGE_INCREASE `FLAT +2` | `&b氷晶の星環` | `PACKED_ICE` | `status`, `offense`, `element`, `ice`, `azure` |
| `status-wizard-elemental-ice-notable` | `1588` | ICE_DAMAGE_INCREASE `FLAT +6` | `&6凍天の極星` | `BLUE_ICE` | `status`, `offense`, `element`, `ice`, `azure` |
| `status-wizard-elemental-ice-penetration` | `1589`〜`1593` | ICE_PENETRATION `FLAT +1` | `&b氷穿の星環` | `PRISMARINE_CRYSTALS` | `status`, `offense`, `element`, `ice`, `azure` |
| `status-wizard-elemental-ice-penetration-notable` | `1594` | ICE_PENETRATION `FLAT +4` | `&6氷界穿孔の極星` | `HEART_OF_THE_SEA` | `status`, `offense`, `element`, `ice`, `azure` |
| `status-wizard-elemental-lightning` | `1595`〜`1599` | LIGHTNING_DAMAGE_INCREASE `FLAT +2` | `&e雷光の星環` | `LIGHTNING_ROD` | `status`, `offense`, `element`, `lightning` |
| `status-wizard-elemental-lightning-notable` | `1600` | LIGHTNING_DAMAGE_INCREASE `FLAT +6` | `&6轟天の極星` | `AMETHYST_BLOCK` | `status`, `offense`, `element`, `lightning` |
| `status-wizard-elemental-lightning-penetration` | `1601`〜`1605` | LIGHTNING_PENETRATION `FLAT +1` | `&e雷穿の星環` | `COPPER_INGOT` | `status`, `offense`, `element`, `lightning` |
| `status-wizard-elemental-lightning-penetration-notable` | `1606` | LIGHTNING_PENETRATION `FLAT +4` | `&6雷界穿孔の極星` | `END_ROD` | `status`, `offense`, `element`, `lightning` |

### アーケインパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-wizard-arcane-path` | `1607`〜`1618` | MAX_MANA `FLAT +8`、MAGIC_ATTACK `SCALAR +0.003`、SKILL_DAMAGE_INCREASE `FLAT +0.3` | `&5星界魔力の星路` | `ENDER_EYE` | `status`, `offense`, `resource`, `mana`, `astral` |
| `status-wizard-arcane-mana` | `1619`〜`1623` | MAX_MANA `FLAT +20` | `&b深淵魔泉の星環` | `LAPIS_BLOCK` | `status`, `resource`, `mana`, `astral` |
| `status-wizard-arcane-mana-notable` | `1624` | MAX_MANA `FLAT +60` | `&6無尽魔泉の極星` | `CONDUIT` | `status`, `resource`, `mana`, `astral` |
| `status-wizard-arcane-magic` | `1625`〜`1629` | MAGIC_ATTACK `SCALAR +0.015` | `&5純魔の星環` | `AMETHYST_SHARD` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-magic-notable` | `1630` | MAGIC_ATTACK `SCALAR +0.05` | `&6純魔奔流の極星` | `END_CRYSTAL` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-intelligence` | `1631`〜`1635` | INTELLIGENCE `SCALAR +0.015` | `&5叡智昇華の星環` | `BOOK` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-intelligence-notable` | `1636` | INTELLIGENCE `SCALAR +0.05` | `&6叡智超越の極星` | `KNOWLEDGE_BOOK` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-skill-damage` | `1637`〜`1641` | SKILL_DAMAGE_INCREASE `FLAT +1.2` | `&d魔力炸裂の星環` | `FIREWORK_STAR` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-skill-damage-notable` | `1642` | SKILL_DAMAGE_INCREASE `FLAT +5` | `&6魔力崩星の極星` | `NETHER_STAR` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-penetration` | `1643`〜`1647` | MAGIC_DEFENSE_PENETRATION_RATE `FLAT +1.2` | `&5魔障穿孔の星環` | `ENDER_PEARL` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-penetration-notable` | `1648` | MAGIC_DEFENSE_PENETRATION_RATE `FLAT +5` | `&6虚空穿孔の極星` | `RESPAWN_ANCHOR` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-cast` | `1649`〜`1653` | CAST_TIME_REDUCTION `FLAT +1.5` | `&d高速詠唱の星環` | `CLOCK` | `status`, `offense`, `mana`, `astral` |
| `status-wizard-arcane-cast-notable` | `1654` | CAST_TIME_REDUCTION `FLAT +6` | `&6無詠唱境界の極星` | `ENCHANTED_BOOK` | `status`, `offense`, `mana`, `astral` |

## パラディン専門枝

パラディンは共通防御24node、Holy 48node、Guardian 48nodeを使う。全120 status nodeは `pointCost: 1`、`unlockCondition.classId: paladin` である。`SCALAR` は基礎値へ加算する割合、`FLAT` は表示単位の実数加算として使い分ける。削除済み ID `1378`、`1396`、`1417`、`1435`、`1456`、`1474` は再利用しない。

### 共通防御パッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-paladin-common-path` | `1362`, `1363`, `1370`, `1371`, `1379`, `1380` | DEFENSE / MAGIC_DEFENSE `SCALAR +0.005`、MAX_HEALTH `FLAT +10` | `&d鉄祷の星路` | `CHAIN` | `status`, `defense`, `durability` |
| `status-paladin-common-defense` | `1364`〜`1368` | DEFENSE `SCALAR +0.015` | `&d聖鋼の星環` | `IRON_CHESTPLATE` | `status`, `defense`, `durability` |
| `status-paladin-common-defense-notable` | `1369` | DEFENSE `SCALAR +0.04` | `&6鉄壁の極星` | `NETHERITE_CHESTPLATE` | `status`, `defense`, `durability` |
| `status-paladin-common-magic-defense` | `1372`〜`1376` | MAGIC_DEFENSE `SCALAR +0.015` | `&d魔護の星環` | `AMETHYST_SHARD` | `status`, `defense`, `astral` |
| `status-paladin-common-magic-defense-notable` | `1377` | MAGIC_DEFENSE `SCALAR +0.04` | `&6星衣の極星` | `ENCHANTED_GOLDEN_APPLE` | `status`, `defense`, `astral` |
| `status-paladin-common-health` | `1381`〜`1385` | MAX_HEALTH `FLAT +25`、VITALITY `SCALAR +0.01` | `&d生命の星環` | `HEART_OF_THE_SEA` | `status`, `resource`, `health`, `durability` |
| `status-paladin-common-health-notable` | `1386` | MAX_HEALTH `FLAT +75`、VITALITY `SCALAR +0.03`、DEFENSE / MAGIC_DEFENSE `SCALAR +0.02` | `&6不屈の極星` | `TOTEM_OF_UNDYING` | `status`, `defense`, `resource`, `health`, `durability` |

### Holyパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-paladin-holy-path` | `1387`, `1388`, `1395`, `1397`, `1404`, `1405`, `1412`, `1413`, `1421`, `1422`, `1429`, `1430` | MAX_SHIELD `FLAT +3`、SUPPORT_POWER `FLAT +1` | `&b聖光の星路` | `SEA_LANTERN` | `status`, `defense`, `shield`, `light` |
| `status-paladin-holy-shield` | `1389`〜`1393` | MAX_SHIELD `FLAT +10` | `&b聖盾の星環` | `IRON_INGOT` | `status`, `defense`, `shield` |
| `status-paladin-holy-shield-notable` | `1394` | MAX_SHIELD `FLAT +30` | `&6大聖盾の極星` | `SHIELD` | `status`, `defense`, `shield` |
| `status-paladin-holy-recharge` | `1398`〜`1402` | SHIELD_RECHARGE_REDUCTION `FLAT +2` | `&b再生聖盾の星環` | `PRISMARINE_CRYSTALS` | `status`, `defense`, `shield`, `resource` |
| `status-paladin-holy-recharge-notable` | `1403` | SHIELD_RECHARGE_REDUCTION `FLAT +8` | `&6瞬光城塞の極星` | `RECOVERY_COMPASS` | `status`, `defense`, `shield`, `resource` |
| `status-paladin-holy-support` | `1406`〜`1410` | SUPPORT_POWER `FLAT +2` | `&a祝祷の星環` | `GLOW_BERRIES` | `status`, `light` |
| `status-paladin-holy-support-notable` | `1411` | SUPPORT_POWER `FLAT +6` | `&6大祝祷の極星` | `BEACON` | `status`, `light` |
| `status-paladin-holy-mana` | `1414`〜`1416`, `1418`, `1419` | MAX_MANA `FLAT +15` | `&b聖泉の星環` | `AMETHYST_SHARD` | `status`, `resource`, `mana`, `azure` |
| `status-paladin-holy-mana-notable` | `1420` | MAX_MANA `FLAT +40`、MP_REGEN `FLAT +1.5` | `&6尽きぬ聖泉の極星` | `CONDUIT` | `status`, `resource`, `mana`, `azure` |
| `status-paladin-holy-offense` | `1423`〜`1427` | ATTACK / STRENGTH `SCALAR +0.01`、SKILL_DAMAGE_INCREASE `FLAT +0.8` | `&f断罪の星環` | `IRON_SWORD` | `status`, `offense`, `strength`, `light` |
| `status-paladin-holy-offense-notable` | `1428` | ATTACK / STRENGTH `SCALAR +0.03`、SKILL_DAMAGE_INCREASE / DEFENSE_PENETRATION_RATE `FLAT +3` | `&6審判の極星` | `GOLDEN_SWORD` | `status`, `offense`, `strength`, `light` |
| `status-paladin-holy-bastion` | `1431`〜`1434`, `1436` | DEFENSE / MAGIC_DEFENSE `SCALAR +0.01`、MAX_SHIELD `FLAT +8` | `&b聖域の星環` | `SHIELD` | `status`, `defense`, `shield`, `light` |
| `status-paladin-holy-bastion-notable` | `1437` | DEFENSE / MAGIC_DEFENSE `SCALAR +0.03`、MAX_SHIELD `FLAT +30`、SUPPORT_POWER `FLAT +5` | `&6天上城塞の極星` | `NETHER_STAR` | `status`, `defense`, `shield`, `light`, `astral` |

### Guardianパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-paladin-guardian-path` | `1438`, `1439`, `1446`, `1447`, `1454`, `1455`, `1463`, `1464`, `1471`, `1472`, `1480`, `1481` | MAX_HEALTH `FLAT +15`、DEFENSE / MAGIC_DEFENSE `SCALAR +0.003` | `&8血鉄の星路` | `CHAIN` | `status`, `defense`, `resource`, `health`, `durability` |
| `status-paladin-guardian-defense` | `1440`〜`1444` | DEFENSE `SCALAR +0.015` | `&8重鎧の星環` | `NETHERITE_CHESTPLATE` | `status`, `defense`, `durability`, `stone` |
| `status-paladin-guardian-defense-notable` | `1445` | DEFENSE `SCALAR +0.04` | `&6不動城壁の極星` | `ANVIL` | `status`, `defense`, `durability`, `stone` |
| `status-paladin-guardian-magic-defense` | `1448`〜`1452` | MAGIC_DEFENSE `SCALAR +0.015` | `&8黒曜護符の星環` | `CRYING_OBSIDIAN` | `status`, `defense`, `durability`, `astral` |
| `status-paladin-guardian-magic-defense-notable` | `1453` | MAGIC_DEFENSE `SCALAR +0.04` | `&6魔断城壁の極星` | `OBSIDIAN` | `status`, `defense`, `durability`, `astral` |
| `status-paladin-guardian-health` | `1457`〜`1461` | MAX_HEALTH `FLAT +45` | `&c巨躯の星環` | `HEART_OF_THE_SEA` | `status`, `resource`, `health`, `durability` |
| `status-paladin-guardian-health-notable` | `1462` | MAX_HEALTH `FLAT +120` | `&6巨神の極星` | `TOTEM_OF_UNDYING` | `status`, `resource`, `health`, `durability` |
| `status-paladin-guardian-life-steal` | `1465`〜`1469` | LIFE_STEAL `FLAT +0.6` | `&c血啜りの星環` | `WITHER_ROSE` | `status`, `offense`, `health` |
| `status-paladin-guardian-life-steal-notable` | `1470` | LIFE_STEAL `FLAT +2` | `&6血盟の極星` | `FERMENTED_SPIDER_EYE` | `status`, `offense`, `health` |
| `status-paladin-guardian-offense` | `1473`, `1475`〜`1478` | ATTACK / STRENGTH `SCALAR +0.01` | `&c反攻の星環` | `IRON_AXE` | `status`, `offense`, `strength`, `ember` |
| `status-paladin-guardian-offense-notable` | `1479` | ATTACK / STRENGTH `SCALAR +0.03` | `&6報復の極星` | `NETHERITE_AXE` | `status`, `offense`, `strength`, `ember` |
| `status-paladin-guardian-recovery` | `1482`〜`1486` | MAX_HEALTH `FLAT +35`、VITALITY `SCALAR +0.01`、HEALING_INCREASE `FLAT +1.5`、HP_REGEN `FLAT +1` | `&c血潮の星環` | `GLISTERING_MELON_SLICE` | `status`, `resource`, `health`, `durability` |
| `status-paladin-guardian-recovery-notable` | `1487` | MAX_HEALTH `FLAT +100`、VITALITY `SCALAR +0.03`、HEALING_INCREASE `FLAT +5`、HP_REGEN `FLAT +2` | `&6不滅の極星` | `ENCHANTED_GOLDEN_APPLE` | `status`, `defense`, `resource`, `health`, `durability` |

### パラディンskill解放

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `skill-paladin-defense-conversion` | `1522` | `skill / paladin_defense_conversion` | `&dディフェンスコンバージョン` | `WARDEN_SPAWN_EGG` | `defense` |
| `skill-paladin-holy-smite` | `1523` | `skill / paladin_holy_smite` | `&fホーリースマイト` | `WAXED_OXIDIZED_COPPER_LANTERN` | `weakness` |
| `skill-paladin-holy-field` | `1524` | `skill / paladin_holy_field` | `&fホーリーフィールド` | `IRON_TRAPDOOR` | `defense` |
| `skill-paladin-holy-smash` | `1525` | `skill / paladin_holy_smash` | `&fホーリースマッシュ` | `music_disc_tears` | `offense` |
| `skill-paladin-divine-chaser` | `1526` | `skill / paladin_divine_chaser` | `&fディバインチェイサー` | `BEACON` | `offense` |
| `skill-paladin-shield` | `1527` | `skill / paladin_shield` | `&fパラディンシールド` | `SHIELD` | `defense` |
| `skill-paladin-holy-control` | `1528` | `skill / paladin_holy_control` | `&fホーリーコントロール` | `COMPARATOR` | `light` |
| `skill-paladin-guard-convert` | `1529` | `skill / paladin_guard_convert` | `&bガードコンバート` | `RESPAWN_ANCHOR` | `defense` |
| `skill-paladin-shield-bash` | `1530` | `skill / paladin_shield_bash` | `&bシールドバッシュ` | `COPPER_GRATE` | `offense` |
| `skill-paladin-shield-impact` | `1531` | `skill / paladin_shield_impact` | `&bシールドインパクト` | `NETHERITE_BLOCK` | `offense`, `defense` |
| `skill-paladin-fortress` | `1532` | `skill / paladin_fortress` | `&3フォートレス` | `REINFORCED_DEEPSLATE` | `defense` |
| `skill-paladin-guardian-protect` | `1533` | `skill / paladin_guardian_protect` | `&bガーディアンプロテクト` | `GLOBE_BANNER_PATTERN` | `defense` |
| `skill-paladin-guardian-chain` | `1534` | `skill / paladin_guardian_chain` | `&3ガーディアンチェイン` | `LEAD` | `defense` |

## ソードマスター専門枝

ソードマスターは共通剣技24node、剣聖48node、剣舞48nodeを使う。全120 status nodeは `pointCost: 1`、`unlockCondition.classId: swordmaster` である。今回はskill使用許可nodeを含めない。

### 共通剣技パッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-swordmaster-common-path` | `1655`, `1656`, `1663`, `1664`, `1671`, `1672` | ATTACK / STRENGTH `SCALAR +0.005`、DEFENSE / MAGIC_DEFENSE `SCALAR +0.003` | `&d剣理の星路` | `IRON_SWORD` | `status`, `offense`, `defense`, `strength` |
| `status-swordmaster-common-offense` | `1657`〜`1661` | ATTACK / STRENGTH `SCALAR +0.01` | `&d剣気の星環` | `IRON_SWORD` | `status`, `offense`, `strength` |
| `status-swordmaster-common-offense-notable` | `1662` | ATTACK / STRENGTH `SCALAR +0.03` | `&6剣豪の極星` | `NETHERITE_SWORD` | `status`, `offense`, `strength` |
| `status-swordmaster-common-accuracy` | `1665`〜`1669` | ACCURACY `FLAT +0.5` | `&d明鏡の星環` | `SPYGLASS` | `status`, `offense`, `agility` |
| `status-swordmaster-common-accuracy-notable` | `1670` | ACCURACY `FLAT +2.5`、CRITICAL_RATE `FLAT +1.5` | `&6無明断ちの極星` | `ENDER_EYE` | `status`, `offense`, `agility` |
| `status-swordmaster-common-defense` | `1673`〜`1677` | MAX_HEALTH `FLAT +20`、DEFENSE / MAGIC_DEFENSE `SCALAR +0.005` | `&d護身の星環` | `CHAINMAIL_CHESTPLATE` | `status`, `defense`, `health`, `durability` |
| `status-swordmaster-common-defense-notable` | `1678` | MAX_HEALTH `FLAT +60`、DEFENSE / MAGIC_DEFENSE `SCALAR +0.02` | `&6金剛身の極星` | `TOTEM_OF_UNDYING` | `status`, `defense`, `health`, `durability` |

### 剣聖パッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-swordmaster-sword-saint-path` | `1679`, `1680`, `1687`, `1688`, `1695`, `1696`, `1703`, `1704`, `1711`, `1712`, `1719`, `1720` | SKILL_DAMAGE_INCREASE `FLAT +0.4`、COOLDOWN_REDUCTION `FLAT +0.2` | `&f一閃の星路` | `LIGHTNING_ROD` | `status`, `offense`, `strength` |
| `status-swordmaster-sword-saint-strike` | `1681`〜`1685` | MELEE_ATTACK `FLAT +2`、SKILL_DAMAGE_INCREASE `FLAT +0.5` | `&f必殺の星環` | `IRON_SWORD` | `status`, `offense`, `strength` |
| `status-swordmaster-sword-saint-strike-notable` | `1686` | MELEE_ATTACK `FLAT +8`、SKILL_DAMAGE_INCREASE `FLAT +3` | `&6一刀両断の極星` | `NETHERITE_SWORD` | `status`, `offense`, `strength` |
| `status-swordmaster-sword-saint-critical` | `1689`〜`1693` | CRITICAL_DAMAGE `FLAT +3` | `&f会心の星環` | `QUARTZ` | `status`, `offense`, `astral` |
| `status-swordmaster-sword-saint-critical-notable` | `1694` | CRITICAL_RATE `FLAT +3`、CRITICAL_DAMAGE `FLAT +10` | `&6天剣の極星` | `NETHER_STAR` | `status`, `offense`, `astral` |
| `status-swordmaster-sword-saint-penetration` | `1697`〜`1701` | MELEE_DEFENSE_PENETRATION_RATE `FLAT +1` | `&f破甲の星環` | `FLINT` | `status`, `offense`, `strength` |
| `status-swordmaster-sword-saint-penetration-notable` | `1702` | MELEE_DEFENSE_PENETRATION_RATE `FLAT +5`、DEFENSE_PENETRATION_RATE `FLAT +2` | `&6無鎧の極星` | `DIAMOND_SWORD` | `status`, `offense`, `strength` |
| `status-swordmaster-sword-saint-parry` | `1705`〜`1709` | DEFENSE / MAGIC_DEFENSE `SCALAR +0.005` | `&b受流しの星環` | `SHIELD` | `status`, `defense`, `durability` |
| `status-swordmaster-sword-saint-parry-notable` | `1710` | DEFENSE / MAGIC_DEFENSE `SCALAR +0.02`、EVASION `FLAT +2` | `&6不動剣の極星` | `TOTEM_OF_UNDYING` | `status`, `defense`, `durability` |
| `status-swordmaster-sword-saint-foresight` | `1713`〜`1717` | ACCURACY `FLAT +0.5`、EVASION `FLAT +0.25` | `&b見切りの星環` | `SPYGLASS` | `status`, `offense`, `defense`, `agility` |
| `status-swordmaster-sword-saint-foresight-notable` | `1718` | ACCURACY `FLAT +2.5`、EVASION `FLAT +1.25`、CRITICAL_RATE `FLAT +2` | `&6明鏡止水の極星` | `ENDER_EYE` | `status`, `offense`, `defense`, `agility` |
| `status-swordmaster-sword-saint-cooldown` | `1721`〜`1725` | COOLDOWN_REDUCTION `FLAT +0.8` | `&f残心の星環` | `CLOCK` | `status`, `offense`, `resource`, `energy` |
| `status-swordmaster-sword-saint-cooldown-notable` | `1726` | COOLDOWN_REDUCTION `FLAT +4`、ENERGY_COST_REDUCTION `FLAT +5` | `&6刹那輪廻の極星` | `RECOVERY_COMPASS` | `status`, `offense`, `resource`, `energy` |

### 剣舞パッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-swordmaster-sword-dance-path` | `1727`, `1728`, `1735`, `1736`, `1743`, `1744`, `1751`, `1752`, `1759`, `1760`, `1767`, `1768` | ATTACK_SPEED `FLAT +0.5`、MAX_ENERGY `FLAT +2` | `&c連舞の星路` | `FEATHER` | `status`, `offense`, `resource`, `energy`, `agility` |
| `status-swordmaster-sword-dance-speed` | `1729`〜`1733` | ATTACK_SPEED `FLAT +1` | `&c疾風の星環` | `FEATHER` | `status`, `offense`, `agility` |
| `status-swordmaster-sword-dance-speed-notable` | `1734` | ATTACK_SPEED `FLAT +4`、MOVEMENT_SPEED `FLAT +4` | `&6神速の極星` | `RABBIT_FOOT` | `status`, `offense`, `agility` |
| `status-swordmaster-sword-dance-combo` | `1737`〜`1741` | MELEE_ATTACK `FLAT +1`、ATTACK `SCALAR +0.005` | `&c連斬の星環` | `IRON_SWORD` | `status`, `offense`, `strength` |
| `status-swordmaster-sword-dance-combo-notable` | `1742` | MELEE_ATTACK `FLAT +5`、ATTACK `SCALAR +0.025` | `&6千刃の極星` | `DIAMOND_SWORD` | `status`, `offense`, `strength` |
| `status-swordmaster-sword-dance-energy` | `1745`〜`1749` | ENERGY_COST_REDUCTION `FLAT +1` | `&b節気の星環` | `HONEY_BOTTLE` | `status`, `resource`, `energy`, `azure` |
| `status-swordmaster-sword-dance-energy-notable` | `1750` | ENERGY_COST_REDUCTION `FLAT +5`、ENERGY_REGEN `FLAT +2` | `&6無窮の極星` | `BEACON` | `status`, `resource`, `energy` |
| `status-swordmaster-sword-dance-sustain` | `1753`〜`1757` | MAX_HEALTH `FLAT +20`、LIFE_STEAL `FLAT +0.2` | `&c不倒の星環` | `GLOW_BERRIES` | `status`, `offense`, `resource`, `health`, `durability` |
| `status-swordmaster-sword-dance-sustain-notable` | `1758` | MAX_HEALTH `FLAT +60`、LIFE_STEAL `FLAT +1.5` | `&6血華の極星` | `ENCHANTED_GOLDEN_APPLE` | `status`, `offense`, `resource`, `health`, `durability` |
| `status-swordmaster-sword-dance-critical` | `1761`〜`1765` | CRITICAL_RATE `FLAT +0.5` | `&d会心の星環` | `QUARTZ` | `status`, `offense` |
| `status-swordmaster-sword-dance-critical-notable` | `1766` | CRITICAL_RATE `FLAT +2.5`、CRITICAL_DAMAGE `FLAT +8` | `&6乱舞の極星` | `NETHER_STAR` | `status`, `offense`, `agility` |
| `status-swordmaster-sword-dance-finale` | `1769`〜`1773` | ATTACK_SPEED `FLAT +1`、SKILL_DAMAGE_INCREASE `FLAT +0.5` | `&c剣舞の星環` | `REDSTONE` | `status`, `offense`, `agility` |
| `status-swordmaster-sword-dance-finale-notable` | `1774` | ATTACK_SPEED `FLAT +4`、SKILL_DAMAGE_INCREASE `FLAT +3`、MOVEMENT_SPEED `FLAT +3` | `&6終演の極星` | `NETHERITE_SWORD` | `status`, `offense`, `agility` |

`1488`〜`1521` は `classId` を持たない汎用PP nodeで、既存の有料PP 32と組み合わせて60PPの消費先を作る。各nodeは1PPで、playerLevel条件は10〜55。`1511`からは攻撃・防御、HP・知力、機動・Shield、命中・回復、STR・魔法防御の2択枝へ分かれる。

## ファントムアーチャー専門枝

ファントムアーチャー用として共通24node、Shadow 48node、Specter 48nodeと、未配置のヒールアローαnode `2256`、ファントムショットnode `2259` を定義する。全120 status nodeとskill nodeは `pointCost: 1`、`unlockCondition.classId: phantom_archer` とする。これらの定義は現行の `starter` には未配置である。

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `skill-phantom-archer-heal-arrow-alpha` | `2256` | `skill` / `archer_heal_arrow_alpha` | `&aヒールアローα` | `GLOW_BERRIES` | `light` |
| `skill-phantom-archer-phantom-shot` | `2259` | `skill` / `sharpshooter_phantom_shot` | `&5ファントムショット` | `GLOW_INK_SAC` | `offense`, `dark`, `astral` |

### 共通パッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-phantom-common-path` | `2000`, `2001`, `2008`, `2009`, `2016`, `2017` | RANGED_ATTACK / DEXTERITY `SCALAR +0.005`、MAX_ENERGY `FLAT +5` | `&5幽弓の星路` | `SPECTRAL_ARROW` | `status`, `offense`, `resource`, `dexterity`, `energy`, `dark` |
| `status-phantom-common-ranged` | `2002`〜`2004`, `2006`, `2007` | RANGED_ATTACK `SCALAR +0.015` | `&5霊矢の星環` | `SPECTRAL_ARROW` | `status`, `offense`, `dark` |
| `status-phantom-common-ranged-notable` | `2005` | RANGED_ATTACK `SCALAR +0.04`、ACCURACY `FLAT +3` | `&6幽弓の極星` | `CROSSBOW` | `status`, `offense`, `accuracy`, `dark` |
| `status-phantom-common-agility` | `2010`〜`2012`, `2014`, `2015` | AGILITY `SCALAR +0.015` | `&5幻歩の星環` | `PHANTOM_MEMBRANE` | `status`, `agility`, `defense`, `dark` |
| `status-phantom-common-agility-notable` | `2013` | AGILITY `SCALAR +0.04`、MOVEMENT_SPEED `FLAT +5`、EVASION `FLAT +2` | `&6虚歩の極星` | `ECHO_SHARD` | `status`, `agility`, `defense`, `dark` |
| `status-phantom-common-energy` | `2018`〜`2020`, `2022`, `2023` | MAX_ENERGY `FLAT +5` | `&5霊脈の星環` | `SOUL_LANTERN` | `status`, `resource`, `energy`, `dark` |
| `status-phantom-common-energy-notable` | `2021` | MAX_ENERGY `FLAT +20`、ENERGY_REGEN `FLAT +2`、ENERGY_COST_REDUCTION `FLAT +3` | `&6冥脈の極星` | `RECOVERY_COMPASS` | `status`, `resource`, `energy`, `dark` |

共通領域の全取得値は RANGED_ATTACK `SCALAR +0.145`、DEXTERITY `SCALAR +0.03`、AGILITY `SCALAR +0.115`、MAX_ENERGY `FLAT +75`、ACCURACY `FLAT +3`、MOVEMENT_SPEED `FLAT +5`、EVASION `FLAT +2`、ENERGY_REGEN `FLAT +2`、ENERGY_COST_REDUCTION `FLAT +3` である。

### Shadowパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-phantom-shadow-path` | `2024`, `2025`, `2032`, `2033`, `2040`, `2041`, `2048`, `2049`, `2056`, `2057`, `2064`, `2065` | RANGED_ATTACK `SCALAR +0.005`、DEXTERITY `SCALAR +0.003`、CONDITION_DURATION_INCREASE `FLAT +1` | `&8影縫いの星路` | `BLACK_DYE` | `status`, `offense`, `condition`, `dexterity`, `dark` |
| `status-phantom-shadow-weakness` | `2026`〜`2028`, `2030`, `2031` | WEAKNESS_APPLY_CHANCE `FLAT +3` | `&8衰印の星環` | `WITHER_ROSE` | `status`, `offense`, `condition`, `weakness`, `dark` |
| `status-phantom-shadow-weakness-notable` | `2029` | WEAKNESS_APPLY_CHANCE `FLAT +10`、CONDITION_DURATION_INCREASE `FLAT +4` | `&6蝕印の極星` | `WITHER_SKELETON_SKULL` | `status`, `offense`, `condition`, `weakness`, `dark` |
| `status-phantom-shadow-blindness` | `2034`〜`2036`, `2038`, `2039` | BLINDNESS_APPLY_CHANCE `FLAT +3` | `&8盲印の星環` | `INK_SAC` | `status`, `offense`, `condition`, `blindness`, `dark` |
| `status-phantom-shadow-blindness-notable` | `2037` | BLINDNESS_APPLY_CHANCE `FLAT +10`、CONDITION_DURATION_INCREASE `FLAT +4` | `&6闇幕の極星` | `SCULK_CATALYST` | `status`, `offense`, `condition`, `blindness`, `dark` |
| `status-phantom-shadow-duration` | `2042`〜`2044`, `2046`, `2047` | CONDITION_DURATION_INCREASE `FLAT +2` | `&8永影の星環` | `CLOCK` | `status`, `condition`, `dark` |
| `status-phantom-shadow-duration-notable` | `2045` | CONDITION_DURATION_INCREASE `FLAT +8`、SKILL_DAMAGE_INCREASE `FLAT +2` | `&6長夜の極星` | `ECHO_SHARD` | `status`, `offense`, `condition`, `dark` |
| `status-phantom-shadow-skill-damage` | `2050`〜`2052`, `2054`, `2055` | SKILL_DAMAGE_INCREASE `FLAT +1` | `&8影撃の星環` | `SPECTRAL_ARROW` | `status`, `offense`, `dark` |
| `status-phantom-shadow-skill-damage-notable` | `2053` | SKILL_DAMAGE_INCREASE `FLAT +4`、COOLDOWN_REDUCTION `FLAT +3` | `&6連影の極星` | `END_CRYSTAL` | `status`, `offense`, `dark` |
| `status-phantom-shadow-penetration` | `2058`〜`2060`, `2062`, `2063` | RANGED_DEFENSE_PENETRATION_RATE `FLAT +1` | `&8破防の星環` | `TIPPED_ARROW` | `status`, `offense`, `accuracy`, `dark` |
| `status-phantom-shadow-penetration-notable` | `2061` | RANGED_DEFENSE_PENETRATION_RATE `FLAT +4`、ACCURACY `FLAT +2` | `&6影穿の極星` | `NETHERITE_PICKAXE` | `status`, `offense`, `accuracy`, `dark` |
| `status-phantom-shadow-critical` | `2066`〜`2068`, `2070`, `2071` | CRITICAL_RATE `FLAT +0.6`、CRITICAL_DAMAGE `FLAT +2` | `&8狩印の星環` | `QUARTZ` | `status`, `offense`, `luck`, `dark` |
| `status-phantom-shadow-critical-notable` | `2069` | CRITICAL_RATE `FLAT +2`、CRITICAL_DAMAGE `FLAT +10`、SKILL_DAMAGE_INCREASE `FLAT +2` | `&6影狩の極星` | `ENDER_EYE` | `status`, `offense`, `luck`, `dark` |

Shadow全取得値は、経路分を含めてRANGED_ATTACK `SCALAR +0.06`、DEXTERITY `SCALAR +0.036`、WEAKNESS_APPLY_CHANCE / BLINDNESS_APPLY_CHANCEを各 `FLAT +25`、CONDITION_DURATION_INCREASE `FLAT +38`、SKILL_DAMAGE_INCREASE `FLAT +13`、COOLDOWN_REDUCTION `FLAT +3`、RANGED_DEFENSE_PENETRATION_RATE `FLAT +9`、ACCURACY `FLAT +2`、CRITICAL_RATE `FLAT +5`、CRITICAL_DAMAGE `FLAT +20` である。

### Specterパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-phantom-specter-path` | `2072`, `2073`, `2080`, `2081`, `2088`, `2089`, `2096`, `2097`, `2104`, `2105`, `2112`, `2113` | SKILL_DAMAGE_INCREASE `FLAT +0.5`、MAX_MANA `FLAT +5`、AGILITY `SCALAR +0.003` | `&d幻影の星路` | `AMETHYST_SHARD` | `status`, `offense`, `resource`, `mana`, `agility`, `astral`, `dark` |
| `status-phantom-specter-cooldown` | `2074`〜`2076`, `2078`, `2079` | COOLDOWN_REDUCTION `FLAT +1.5` | `&d霊招の星環` | `CLOCK` | `status`, `resource`, `energy`, `astral`, `dark` |
| `status-phantom-specter-cooldown-notable` | `2077` | COOLDOWN_REDUCTION `FLAT +5`、ENERGY_COST_REDUCTION `FLAT +2` | `&6百鬼招来の極星` | `RECOVERY_COMPASS` | `status`, `resource`, `energy`, `astral`, `dark` |
| `status-phantom-specter-skill-damage` | `2082`〜`2084`, `2086`, `2087` | SKILL_DAMAGE_INCREASE `FLAT +1` | `&d幻撃の星環` | `GHAST_TEAR` | `status`, `offense`, `astral`, `dark` |
| `status-phantom-specter-skill-damage-notable` | `2085` | SKILL_DAMAGE_INCREASE `FLAT +4`、RANGED_ATTACK `SCALAR +0.03` | `&6幻軍の極星` | `NETHER_STAR` | `status`, `offense`, `astral`, `dark` |
| `status-phantom-specter-mana` | `2090`〜`2092`, `2094`, `2095` | MAX_MANA `FLAT +12` | `&d霊泉の星環` | `SOUL_LANTERN` | `status`, `resource`, `mana`, `astral`, `dark` |
| `status-phantom-specter-mana-notable` | `2093` | MAX_MANA `FLAT +30`、MP_REGEN `FLAT +2` | `&6幽泉の極星` | `CONDUIT` | `status`, `resource`, `mana`, `astral`, `dark` |
| `status-phantom-specter-energy` | `2098`〜`2100`, `2102`, `2103` | ENERGY_COST_REDUCTION `FLAT +1.5` | `&d省霊の星環` | `HONEY_BOTTLE` | `status`, `resource`, `energy`, `astral`, `dark` |
| `status-phantom-specter-energy-notable` | `2101` | ENERGY_COST_REDUCTION `FLAT +5`、ENERGY_REGEN `FLAT +2` | `&6霊環の極星` | `ECHO_SHARD` | `status`, `resource`, `energy`, `astral`, `dark` |
| `status-phantom-specter-dual-primary` | `2106`〜`2108`, `2110`, `2111` | DEXTERITY / INTELLIGENCE `SCALAR +0.01` | `&d双魂の星環` | `AMETHYST_SHARD` | `status`, `primary`, `dexterity`, `intelligence`, `astral`, `dark` |
| `status-phantom-specter-dual-primary-notable` | `2109` | DEXTERITY / INTELLIGENCE `SCALAR +0.03` | `&6共鳴の極星` | `END_CRYSTAL` | `status`, `primary`, `dexterity`, `intelligence`, `astral`, `dark` |
| `status-phantom-specter-mobility` | `2114`〜`2116`, `2118`, `2119` | AGILITY `SCALAR +0.01`、MOVEMENT_SPEED `FLAT +1` | `&d霊渡りの星環` | `PHANTOM_MEMBRANE` | `status`, `agility`, `defense`, `astral`, `dark` |
| `status-phantom-specter-mobility-notable` | `2117` | AGILITY `SCALAR +0.03`、MOVEMENT_SPEED `FLAT +5`、EVASION `FLAT +3` | `&6幽界渡りの極星` | `ELYTRA` | `status`, `agility`, `defense`, `astral`, `dark` |

Specter全取得値は、経路分を含めてSKILL_DAMAGE_INCREASE `FLAT +15`、MAX_MANA `FLAT +150`、AGILITY `SCALAR +0.116`、COOLDOWN_REDUCTION `FLAT +12.5`、ENERGY_COST_REDUCTION `FLAT +14.5`、RANGED_ATTACK `SCALAR +0.03`、MP_REGEN / ENERGY_REGENを各 `FLAT +2`、DEXTERITY / INTELLIGENCEを各 `SCALAR +0.08`、MOVEMENT_SPEED `FLAT +10`、EVASION `FLAT +3` である。


## シャープシューター専門枝

シャープシューター用として共通射撃24node、Sniper 48node、Rapid 48node、未配置の属性矢強化12node（`2243`〜`2254`）、skill解放node 6個を定義する。全status nodeとskill nodeは `pointCost: 1`、`unlockCondition.classId: sharpshooter` とする。`2240` は `sharpshooter_inheritance_mastery` の使用許可を与え、共通射撃node `2120` から独立leafとして相対座標 `(-14.5, 0, -16.0)` に配置する。`2241`、`2242`、`2255`、`2257`、`2258` は使用許可だけを定義した未配置nodeとする。

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `skill-sharpshooter-inheritance-mastery` | `2240` | `skill` / `sharpshooter_inheritance_mastery` | `&a継承の心得` | `SPECTRAL_ARROW` | `offense`, `wind` |
| `skill-sharpshooter-fire-arrow` | `2241` | `skill` / `sharpshooter_fire_arrow` | `&6ファイアアロー` | `FIRE_CORAL` | `offense`, `fire`, `burning` |
| `skill-sharpshooter-ice-arrow` | `2242` | `skill` / `sharpshooter_ice_arrow` | `&bアイスアロー` | `LIGHT_BLUE_DYE` | `offense`, `ice`, `frozen` |
| `skill-sharpshooter-heal-arrow-alpha` | `2255` | `skill` / `archer_heal_arrow_alpha` | `&aヒールアローα` | `GLOW_BERRIES` | `light` |
| `skill-sharpshooter-spreading-ambition` | `2257` | `skill` / `sharpshooter_spreading_ambition` | `&a拡散する野望` | `TORCHFLOWER_SEEDS` | `offense` |
| `skill-sharpshooter-phantom-shot` | `2258` | `skill` / `sharpshooter_phantom_shot` | `&5ファントムショット` | `GLOW_INK_SAC` | `offense`, `dark`, `astral` |

### 共通射撃パッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-sharpshooter-common-path` | `2120`, `2121`, `2128`, `2129`, `2136`, `2137` | RANGED_ATTACK `FLAT +1`、ACCURACY `FLAT +0.5` | `&d銀矢の星路` | `ARROW` | `status`, `offense`, `accuracy`, `wind` |
| `status-sharpshooter-common-power` | `2122`〜`2126` | RANGED_ATTACK `FLAT +1`、DEXTERITY `SCALAR +0.005` | `&d遠矢の星環` | `BOW` | `status`, `offense`, `dexterity`, `wind` |
| `status-sharpshooter-common-power-notable` | `2127` | RANGED_ATTACK `FLAT +4`、DEXTERITY `SCALAR +0.005` | `&6天穹射の極星` | `CROSSBOW` | `status`, `offense`, `dexterity`, `wind` |
| `status-sharpshooter-common-evasion` | `2130`〜`2134` | EVASION `FLAT +0.5`、MOVEMENT_SPEED `FLAT +1` | `&d風避の星環` | `FEATHER` | `status`, `agility`, `defense`, `wind` |
| `status-sharpshooter-common-evasion-notable` | `2135` | EVASION `FLAT +1.5`、MOVEMENT_SPEED `FLAT +4` | `&6空走の極星` | `ELYTRA` | `status`, `agility`, `defense`, `wind` |
| `status-sharpshooter-common-resource` | `2138`〜`2142` | ACCURACY `FLAT +1`、MAX_ENERGY `FLAT +5` | `&d狩気の星環` | `ENDER_PEARL` | `status`, `resource`, `energy`, `accuracy` |
| `status-sharpshooter-common-resource-notable` | `2143` | ACCURACY `FLAT +3`、MAX_ENERGY `FLAT +40`、ENERGY_REGEN `FLAT +3`、SUPER_CRITICAL_RATE `FLAT +1`、SUPER_CRITICAL_DAMAGE `FLAT +5` | `&6星狩の極星` | `NETHER_STAR` | `status`, `resource`, `energy`, `accuracy`, `astral` |

### Sniperパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-sharpshooter-sniper-path` | `2144`, `2145`, `2152`, `2153`, `2160`, `2161`, `2168`, `2169`, `2176`, `2177`, `2184`, `2185` | RANGED_ATTACK `FLAT +1`、NORMAL_ATTACK_DAMAGE_INCREASE `FLAT +0.75` | `&b精密射の星路` | `SPECTRAL_ARROW` | `status`, `offense`, `accuracy`, `wind` |
| `status-sharpshooter-sniper-power` | `2146`〜`2150` | RANGED_ATTACK `FLAT +2`、DEXTERITY `SCALAR +0.01` | `&b剛弓の星環` | `BOW` | `status`, `offense`, `dexterity`, `wind` |
| `status-sharpshooter-sniper-power-notable` | `2151` | RANGED_ATTACK `FLAT +6`、DEXTERITY `SCALAR +0.03` | `&6破城弓の極星` | `CROSSBOW` | `status`, `offense`, `dexterity`, `wind` |
| `status-sharpshooter-sniper-accuracy` | `2154`〜`2158` | ACCURACY `FLAT +2.5` | `&b照準の星環` | `SPYGLASS` | `status`, `offense`, `accuracy` |
| `status-sharpshooter-sniper-accuracy-notable` | `2159` | ACCURACY `FLAT +5.5`、RANGED_DEFENSE_PENETRATION_RATE `FLAT +6` | `&6必中の極星` | `TARGET` | `status`, `offense`, `accuracy` |
| `status-sharpshooter-sniper-critical` | `2162`〜`2166` | CRITICAL_RATE `FLAT +0.5`、CRITICAL_DAMAGE `FLAT +1` | `&b会心の星環` | `AMETHYST_SHARD` | `status`, `offense`, `astral` |
| `status-sharpshooter-sniper-critical-notable` | `2167` | CRITICAL_RATE `FLAT +1.5`、CRITICAL_DAMAGE `FLAT +10` | `&6致命射の極星` | `RECOVERY_COMPASS` | `status`, `offense`, `astral` |
| `status-sharpshooter-sniper-super-critical` | `2170`〜`2174` | SUPER_CRITICAL_RATE `FLAT +0.5`、SUPER_CRITICAL_DAMAGE `FLAT +1` | `&d超星狙撃の星環` | `ECHO_SHARD` | `status`, `offense`, `astral` |
| `status-sharpshooter-sniper-super-critical-notable` | `2175` | SUPER_CRITICAL_RATE `FLAT +1.5`、SUPER_CRITICAL_DAMAGE `FLAT +10` | `&6超星穿の極星` | `NETHER_STAR` | `status`, `offense`, `astral` |
| `status-sharpshooter-sniper-lightning` | `2178`〜`2182` | LIGHTNING_DAMAGE_INCREASE `FLAT +2`、SKILL_DAMAGE_INCREASE `FLAT +1.5` | `&e雷矢の星環` | `LIGHTNING_ROD` | `status`, `offense`, `lightning`, `shocked` |
| `status-sharpshooter-sniper-lightning-notable` | `2183` | LIGHTNING_DAMAGE_INCREASE `FLAT +8`、LIGHTNING_PENETRATION `FLAT +6`、SKILL_DAMAGE_INCREASE `FLAT +5` | `&6天雷の極星` | `LIGHTNING_ROD` | `status`, `offense`, `lightning`, `shocked` |
| `status-sharpshooter-ice-arrow` | `2243`〜`2247` | ICE_DAMAGE_INCREASE `FLAT +2`、SKILL_DAMAGE_INCREASE `FLAT +1.5` | `&b氷矢の星環` | `PACKED_ICE` | `status`, `offense`, `ice`, `frozen`, `azure` |
| `status-sharpshooter-ice-arrow-notable` | `2248` | ICE_DAMAGE_INCREASE `FLAT +8`、ICE_PENETRATION `FLAT +6`、SKILL_DAMAGE_INCREASE `FLAT +5` | `&6凍穿の極星` | `BLUE_ICE` | `status`, `offense`, `ice`, `frozen`, `azure`, `astral` |
| `status-sharpshooter-fire-arrow` | `2249`〜`2253` | FIRE_DAMAGE_INCREASE `FLAT +2`、SKILL_DAMAGE_INCREASE `FLAT +1.5` | `&c炎矢の星環` | `BLAZE_POWDER` | `status`, `offense`, `fire`, `burning`, `ember` |
| `status-sharpshooter-fire-arrow-notable` | `2254` | FIRE_DAMAGE_INCREASE `FLAT +8`、FIRE_PENETRATION `FLAT +6`、SKILL_DAMAGE_INCREASE `FLAT +5` | `&6炎穿の極星` | `FIRE_CHARGE` | `status`, `offense`, `fire`, `burning`, `ember`, `astral` |
| `status-sharpshooter-sniper-evasion` | `2186`〜`2190` | EVASION `FLAT +0.5`、MOVEMENT_SPEED `FLAT +1` | `&b残影の星環` | `RABBIT_FOOT` | `status`, `agility`, `defense`, `wind` |
| `status-sharpshooter-sniper-evasion-notable` | `2191` | EVASION `FLAT +1.5`、MOVEMENT_SPEED `FLAT +4` | `&6幻走の極星` | `ELYTRA` | `status`, `agility`, `defense`, `wind` |

### Rapidパッケージ

| カタログ ID | nodeId | 効果 | 表示名 | アイコン | タグ |
|:--|:--|:--|:--|:--|:--|
| `status-sharpshooter-rapid-path` | `2192`, `2193`, `2200`, `2201`, `2208`, `2209`, `2216`, `2217`, `2224`, `2225`, `2232`, `2233` | RANGED_ATTACK `FLAT +1`、MAX_ENERGY `FLAT +5` | `&a連射の星路` | `TIPPED_ARROW` | `status`, `offense`, `resource`, `energy`, `wind` |
| `status-sharpshooter-rapid-power` | `2194`〜`2198` | RANGED_ATTACK `FLAT +2`、DEXTERITY `SCALAR +0.01` | `&a連弓の星環` | `BOW` | `status`, `offense`, `dexterity`, `wind` |
| `status-sharpshooter-rapid-power-notable` | `2199` | RANGED_ATTACK `FLAT +6`、DEXTERITY `SCALAR +0.03` | `&6連弩の極星` | `CROSSBOW` | `status`, `offense`, `dexterity`, `wind` |
| `status-sharpshooter-rapid-speed` | `2202`〜`2206` | ATTACK_SPEED `FLAT +2`、AGILITY `SCALAR +0.01` | `&a速射の星環` | `CLOCK` | `status`, `offense`, `agility`, `wind` |
| `status-sharpshooter-rapid-speed-notable` | `2207` | ATTACK_SPEED `FLAT +8`、AGILITY `SCALAR +0.03`、CRITICAL_RATE `FLAT +3`、CRITICAL_DAMAGE `FLAT +10` | `&6疾風連射の極星` | `ELYTRA` | `status`, `offense`, `agility`, `wind`, `astral` |
| `status-sharpshooter-rapid-normal` | `2210`〜`2214` | NORMAL_ATTACK_DAMAGE_INCREASE `FLAT +2`、NORMAL_ATTACK_DEGRADATION_DELAY `FLAT +0.5` | `&a連矢の星環` | `ARROW` | `status`, `offense`, `wind` |
| `status-sharpshooter-rapid-normal-notable` | `2215` | NORMAL_ATTACK_DAMAGE_INCREASE `FLAT +8`、NORMAL_ATTACK_DEGRADATION_DELAY `FLAT +2.5` | `&6無窮連矢の極星` | `SPECTRAL_ARROW` | `status`, `offense`, `wind`, `astral` |
| `status-sharpshooter-rapid-sustain` | `2218`〜`2222` | ENERGY_COST_REDUCTION `FLAT +1` | `&a循環の星環` | `ENDER_PEARL` | `status`, `resource`, `energy` |
| `status-sharpshooter-rapid-sustain-notable` | `2223` | ENERGY_COST_REDUCTION / ENERGY_REGEN `FLAT +4` | `&6不息の極星` | `BEACON` | `status`, `resource`, `energy`, `astral` |
| `status-sharpshooter-rapid-lightning` | `2226`〜`2230` | LIGHTNING_DAMAGE_INCREASE / SHOCKED_APPLY_CHANCE `FLAT +2`、NORMAL_ATTACK_DAMAGE_INCREASE `FLAT +1` | `&e電導の星環` | `LIGHTNING_ROD` | `status`, `offense`, `lightning`, `shocked` |
| `status-sharpshooter-rapid-lightning-notable` | `2231` | LIGHTNING_DAMAGE_INCREASE `FLAT +8`、SHOCKED_APPLY_CHANCE `FLAT +10`、NORMAL_ATTACK_DAMAGE_INCREASE `FLAT +4` | `&6雷群の極星` | `LIGHTNING_ROD` | `status`, `offense`, `lightning`, `shocked`, `astral` |
| `status-sharpshooter-rapid-evasion` | `2234`〜`2238` | EVASION `FLAT +0.5`、MOVEMENT_SPEED `FLAT +1` | `&a疾駆の星環` | `FEATHER` | `status`, `agility`, `defense`, `wind` |
| `status-sharpshooter-rapid-evasion-notable` | `2239` | EVASION `FLAT +1.5`、MOVEMENT_SPEED `FLAT +4` | `&6風翔の極星` | `ELYTRA` | `status`, `agility`, `defense`, `wind` |


## カタログの更新規約

- 新しい能力を採用したときは、対応する node JSON と同じ変更でこの表に追加します。
- 既存能力の表示名、アイコン、タグ、コストを変更するときは、カタログの該当行と使用中の node JSON を同時に確認します。
- 同じ能力を職業別ノードへ置く場合も、このカタログの表現を使います。プレイヤーへ提示する役割、職業条件、配置構造の違いだけを理由に別の名称・アイコン・タグを作りません。
- このカタログは地域や座標を管理しません。地域・配置構造の方針は [[35-skilltree]]、最終座標と edge は `structures/*.json` を正本とします。
