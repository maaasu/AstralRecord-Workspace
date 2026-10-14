# 既存フィールド調査

基点bc7877ae3。enemy全47種=通常field spawner直接参照25種+ダンジョン専用16種+直接参照なし6種。表はfield分類25種。デバッグ、ボス、NPC、ダンジョン専用、召喚専用は素材追加E01に含めない。

共通enemyパス: 40_filebase/40.features.mob/enemy/v1.<種ID>.yml。spawnerパス: 40_filebase/41.features.mob.spawner/v1.<種ID>_lv<level>_spawner.yml (各level1ファイル)。表のdropは直接itemで、別に既存共通lootTableも保持する。単位は確率%。数値の / はlevel順。

| E01素材予約 | 種ID | levels | 直接drop | skill ID | 共通loot |
|---|---|---|---|---|---|
|10a00300|eriva_bark_sentinel|18|10a00100@5|mob_eriva_bark_root_sweep|tier_2|
|10a00301|eriva_camouflage_zombie|18,20,22,24,26,28,30|なし|なし|tier_2|
|10a00302|eriva_grassboar|15,16,17,18,19,20|10a00067@40;10a00068@35|なし|tier_2|
|10a00303|eriva_sporecap_beast|18|10a00100@5|mob_eriva_spore_burst|tier_2|
|10a00304|eriva_starhorn_guardian|15|10a00100@5|mob_eriva_starhorn_grove_circle|tier_2|
|10a00305|eriva_thornfang_wolf|17|10a00100@5|mob_eriva_thornfang_pounce|tier_2|
|10a00306|eriva_underbrush_spider|15|10a00100@5|mob_forest_spider_web_shot|tier_2|
|10a00307|maril_sil_dead_hero|10|10a00058@50;20a00053@0.1|なし|tier_1|
|10a00308|midgard_clay_guard|3|10a00027@70;10a00029@8|mob_clay_guard_leap|tier_1|
|10a00309|midgard_forest_spider|7,8|10a00042@50/45;10a00041@1|mob_forest_spider_web_shot|tier_1|
|10a00310|midgard_forest_wolf|6,7,8|10a00039@50/55/45;10a00038@4/5/3|なし|tier_1|
|10a00311|midgard_grassboar|1,2|10a00001@80;10a00002@35|なし|tier_1|
|10a00312|midgard_moss_shell|2|10a00020@60|mob_moss_shell_shell_bash|tier_1|
|10a00313|midgard_savanna_camel|7,8|10a00043@50/45;10a00050@3/2|mob_clay_guard_leap|tier_1|
|10a00314|midgard_savanna_hyena|7,8|10a00043@50/45;10a00051@3/2|なし|tier_1|
|10a00315|midgard_savanna_witch|7,8|10a00043@50/45;10a00052@3/2|mob_savanna_witch_ember_bolt|tier_1|
|10a00316|midgard_skeleton_archer|2,3|10a00023@75;10a00024@35;10a00026@12|mob_skeleton_bow_shot|tier_1|
|10a00317|purple_tree_golm|16|なし|mob_purple_tree_charge;mob_purple_tree_slam|tier_2|
|10a00318|purple_tree_melza|16|なし|mob_purple_tree_bolt;mob_purple_tree_curse|tier_2|
|10a00319|silmaril_skeleton|9|10a00056@50;20a00053@0.1|mob_skeleton_bow_shot|tier_1|
|10a00320|verdia_cow|20|10a00093@100|なし|tier_2|
|10a00321|verdia_mooshroom|20|10a00094@100|なし|tier_2|
|10a00322|witch_forest_bramble_scarecrow|20|なし|mob_witch_forest_bramble_line|tier_2|
|10a00323|witch_forest_curseweaver_spider|20|なし|mob_witch_forest_curse_web|tier_2|
|10a00324|witch_forest_hex_witch|20|なし|mob_witch_forest_hex_bolt|tier_2|

starhorn/underbrushのspawner例外: v1.eriva_great_forest_lv15_spawner.yml（混合）。共通lootの正式IDはnormal_enemy_common_table_tier_1 / normal_enemy_common_table_tier_2。共通poolは低確率0.005〜12%などを含むので上表と合算せず保持する。抽選が独立かweightか、数量・pool/table連鎖を各実装前に再読する。

ダンジョン専用16種: ainua, ainurindale, eriva_gale_wolf, eriva_reed_archer, eriva_stonehorn_ram, flower_archer_skeleton, manor_bough_archer, manor_greenflame_hexer, manor_rootweaver, manor_thorn_guard, middle_earth_ruins_piglin_charger, middle_earth_ruins_skeleton, middle_earth_ruins_skeleton_archer, moss_shelled_beast, vatar_skeleton, vine_spider。
直接参照なし6種: debug_twilight_mine_enemy, empusa_coven_witch, selenea_lunar_page, selenea_mercy_page, selenea_ward_page, selenea_wrath_page。直接参照なしは未使用と断定しない。コードによる召喚を別途確認する。

現行materialは10a00001〜10a00101の101件。10a00200〜00399の全856YAML参照検索0件。アイテムIDは2桁カテゴリ+英字1桁+5桁番号。levelsは同一種内の複数個体表現。spawner spawnMobs[].levelは定義済みlevelを参照する。
