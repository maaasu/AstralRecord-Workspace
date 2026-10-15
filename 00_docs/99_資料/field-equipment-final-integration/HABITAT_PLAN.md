# 新規23種 生息配置案（読み取り専用調査）

調査対象: `E:\AstralRecord-Worktrees\field-equipment-final-integration`。確認元は `00_docs/99_資料/field-mobs-lv50/PLAN.md`、B01〜B12のSPEC/registration manifest、`40_filebase/40.features.mob/enemy/v1.field_*.yml`、既存world/spawner YAML、41-mob_spawner feature/schema。

## 提案グループ

以下は設計上の生息地・帯を維持してspawner準備単位に束ねた案。23種のレベルprofileはYAMLの `levels[]` 実値を記載。Biome列はmob YAMLに biome フィールドがなく、tags は分類タグのため実Minecraft biome値として確定できない。確定までBiome条件は保留。

| 生息地グループ | 配置案（種ID / 日本語表示名 / 実profile） | 根拠・候補world/region | 状態 |
|---|---|---|---|
| 平原A（2種） | `field_reed_hound` クサハウンド 22/24/26/28；`field_brush_crab` ヤブガニ 22/24/26/28 | B01: 既存迷彩ゾンビ Lv22/24/26/28 と共存案。参照spawner region「エリヴァ大草原」。world不明 | 配置未確定。region根拠あり、座標・world・密度未確認 |
| 平原B（3種） | `field_sunhare` ヒダマリウサギ 28/30/32/34；`field_wind_lizard` カゼトカゲ 28/30/32/34；`field_plain_ram` ハラノヒツジ 28/30/32/34 | PLAN/B02は平原中位帯。B03のヒツジは候補world `vanaheim_supercontinent` と既存迷彩ゾンビ Lv28/30（region「エリヴァ大草原」）を参照 | ヒツジのみworld候補あり（確定でない）。他2種はworld/実座標未確認 |
| 平原C（3種） | `field_blade_mantis` カマキリソード 42/44/46/48；`field_stoneback_bison` イワセバイソン Lv.42 42/44/46/48；`field_storm_raptor` アラシラプター Lv.42 42/44/46/48 | PLAN/B03-B04。B04は `vanaheim_supercontinent` /「エリヴァ大草原」既存spawnerを参照するが平原Cの区域・座標・密度根拠なし。カマキリmanifestは候補なし | 全3種配置保留。Lv31+装備/class実値による戦闘受入も未確定 |
| 森林・初期帯（3種） | `field_bark_beetle` キノカブト 24/26/28/30；`field_moss_fox` コケギツネ 24/26/28/30；`field_root_turtle` ネッコガメ 24/26/28/30 | PLAN/B05-B06。既存「エリヴァ大森林」spawner、B06は候補資料 `v1.verdia.yml` も挙げる。既存資料では森林regionは確認できるが、このレベル帯の座標未確認。迷彩ゾンビは草原regionで森林根拠に使わない | 配置保留。候補worldを選定し実際の森林区域を確かめる必要 |
| 森林・上位帯（3種） | `field_canopy_owl` コノハフクロウ 36/38/40/42；`field_thorn_deer` トゲシカ 36/38/40/42；`field_shade_hedgehog` コカゲハリネズミ 36/38/40/42 | PLAN/B11-B12。3 manifestともworld・実配置の根拠なし | 配置保留。初期帯と同一world/regionにできるかも未確認 |
| 雪原（3種） | `field_snow_wolf` ユキオオカミ 32/34/36/38；`field_ice_moth` コオリガ 32/34/36/38；`field_frost_boar` シモイノシシ 32/34/36/38 | PLAN/B06-B07。manifestsは既存world/spawnerに雪原根拠なしと明記 | 配置blocked。雪原world/region/biomeを指定する入力が必要 |
| メサ（3種） | `field_red_scorpion` アカサソリ 38/40/42/44；`field_sand_gecko` スナヤモリ 38/40/42/44；`field_mesa_vulture` メサハゲワシ 38/40/42/44 | PLAN/B08-B09。manifestsに配置world/座標の根拠なし | 配置blocked。メサworld/region/biomeを指定する入力が必要 |
| 火山（3種） | `field_ember_slug` ヒダネナメクジ 44/46/48/50；`field_lava_crab` ヨウガンガニ 44/46/48/50；`field_ash_drake` ハイドレイク 44/46/48/50 | PLAN/B09-B10。manifestsに配置world/座標の根拠なし。Slugは火山系地形表記 | 配置保留。火山world/region/biomeを指定する入力が必要 |

## 既存参照の扱い

- `60.features.world/v1.starreach_wilds.yml` の実IDは `vanaheim_supercontinent`（表示名「エリヴァ超大陸」）で、worldマスタ自体は存在する。ただし `autoLoad: false` / `instanceEnabled: false`。B03-B04が平原B/Cの候補として引用するが、運用中の実配置や座標を証明しない。
- `41.features.mob.spawner` の既存spawnerは `region` と `radiusMeters` を持つが、world ID・配置座標を含まない。座標は実行環境の `mob_spawners.yml` に保存するスキーマ仕様。
- 「エリヴァ大草原」既存spawner（迷彩ゾンビ等）はregion名とlevelの根拠に限る。各配置worldはYAMLから確定できない。
- 森林既存例は `eriva_great_forest`（region「エリヴァ大森林」）と、forest系の敵/技の例。B06 manifestは `v1.verdia.yml` も証拠に挙げるが、既存spawnerへのworld割当やlevel24〜30実座標は示さない。
- 雪原・メサ・火山について、確認した現行world/spawner資料に対応する実配置根拠は見つからなかった。Biome IDを推測して埋めない。

## 実配置の未確定入力

1. 実際に配置するworld IDと有効化・運用状態、ならびにspawnerのregion/channel名（regionをプレイヤー地域判定に使う場合の表示名）。
2. 候補worldごとの実座標または探索可能な領域/範囲。地形サンプルを確認し、spawner設置可能地点を確定する。
3. 既存spawnerの `mob_spawners.yml` 実配置、既存同時数/密度、周辺上限への寄与、再出現の実測条件。YAMLの標準値だけで現地密度を推定しない。
4. region保護、スポーン許可、建築保護、移動/帰還境界、地形の平坦性・天井・液体・落下/溶岩等の制約。
5. グループごとの配置ルール: 個別/混合spawnerのどちらか、混合する場合の同一進行帯・戦闘目的、各 `spawnMobs[].level` のexact値、抽選weight、地域名の統一。
6. 配置先のBiome条件を使うなら、実worldの正式なMinecraft biome IDと対象地点の照合結果。mob master/spawner schema側にBiome条件キーがあるかは今回の参照schemaでは定義されていないため、設定方法自体の確認も必要。
7. runtime側の命名規則/既存配置IDとの照合、配置担当と `mob_spawners.yml` 更新手順。spawner IDは設計上 `<対象ID>_lv<レベル>_spawner`、複数Mob抽選は群を表すID。
8. 高位帯の実配置判断にはLv31以降の正式な装備/class/skilltree基準と実戦TTK/耐久の受入。統合レビューはこれを保留事項としている。

設定作成前のゲート: world/実座標・region/channel・地形/保護・密度の確認後に限り、必要な新規spawner定義を作り、設置座標は実環境で登録する。座標・半径・上限・出現weight・実Biome値は本案で割り当てていない。
