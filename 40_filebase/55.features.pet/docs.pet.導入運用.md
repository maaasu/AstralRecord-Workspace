# ペットマスターの導入と素材根拠

## 初期パッケージ

| 種類 | 卵item | 個体item | 孵化時の追加素材 |
|:--|:--|:--|:--|
| 犬 | 81a00001 | 80a00001 | 森灯り茸（10a00076）128個 |
| 猫 | 81a00002 | 80a00002 | 青しずくの花びら（10a00077）128個 |
| ニワトリ | 81a00003 | 80a00003 | 金風花の花びら（10a00074）128個 |

全種類で追加素材に加えて、大地の心花びら（10a00084）256個、大地の命玉（10a00085）16個を消費します。卵では種類と要求素材だけを閲覧でき、個体能力は孵化後に公開します。

## 採集素材の確認

ワールド `60.features.world/v1.verdia.yml` は `id: verdia`、表示名「豊穣の大地ヴェルディア」です。マスター上の採集系スポナーは `verdia_meadow_flora_spawner` / `verdia_forest_flora_spawner` / `verdia_waterside_flora_spawner` です。いずれも `gathering:verdia_earthheart_flower` を重み1で参照し、総重み100の最終枠になっています。

`42.features.gathering/harvesting/v1.verdia_earthheart_flower.yml` は `category: HARVESTING`、`level: 2`、`requiredToolTags: [HOE]` です。dropは `item:10a00084` が100%・1個、`item:10a00085` が5%・1個です。item側の表示名は `10.material/v1.10a00084.verdia_earthheart_petal.yml` と `v1.10a00085.verdia_earthlife_gem.yml` が正本です。

種別追加素材の採集オブジェクトもHARVESTING・level2・HOEであり、対応する素材を100%・1個ドロップします。

| 採集オブジェクト | 通常drop | 参照する採集スポナー |
|:--|:--|:--|
| verdia_forestlight_mushroom | 10a00076 | verdia_forest_flora_spawner |
| verdia_blue_dew_flower | 10a00077 | verdia_waterside_flora_spawner |
| verdia_goldwind_flower | 10a00074 | verdia_meadow_flora_spawner |

`20.equipment/v1.20a00072.cave_copper_hoe.yml` の洞窟銅のクワは `tag: HOE`、`GATHERING_LEVEL: 2` です。PluginのGatheringServiceは装備由来GATHERING_LEVELとオブジェクトlevel、tool tagを両方検証します。今回の素材にMININGの鉱石・採掘素材は含めていません。

命玉16個の期待採集量はluck補正を除いて大地の心花320回です。通常素材256個と併せ、最終採集枠を継続して採取する重い孵化コストです。実際の所要時間はスポナーの現地配置・採集人数・luckによって変わるため、この値から時間を断定しません。

採集スポナーの実配置はPluginデータフォルダの `gathering_spawners.yml` に保存され、ワールドYAMLでは指定しません。保存ファイル名はGatheringSpawnerLocationRepositoryの定数で確認しています。リポジトリには配置ファイルがないため、現在のヴェルディア内の座標と配置数は未確認です。

## 育成と配合

初期は成長度1、20、50でスキル枠を解放し、成長度100を上限とします。配合は双方20以上、同種のオスとメス、生存、24時間の配合待機終了を条件に、心花びら64個と命玉4個を消費します。野生のステータス上限はT4、配合上限はT10です。

成長度の必要経験値はAccountServiceのプレイヤー曲線から微小な個人hash加算だけを除いた同形です。召喚・生存・実活動時の付与比率は1です。基礎式の累積必要経験値はLv20で320,395、Lv50で4,988,910、Lv100で39,931,545になります。ログイン時間だけでは増えません。

## 施設と復活用品

`mob:pet_center` の右クリックは `PET_CENTER` を開き、孵化・配合・施設復活を提供します。施設復活は心花びら16個と命玉1個を消費します。左クリックはNPC専用の `pet_support_exchange` を開き、心花びら64個・命玉4個・1,000 Goldを復活オーブ `40a00020` 1個へ交換します。オーブの効果型は `PET_REVIVE` です。

NPCマスターはテンプレートです。拠点の実配置は既存運用に合わせ `/mob npc place pet_center` で行い、Pluginデータフォルダの `npc_locations.yml` へ保存します。リポジトリから現地座標は確認できないため、既存NPC座標を推測して追加しません。

卵とペットのBukkitアイコンはそれぞれEGGと種別SPAWN_EGGです。犬のWOLF_SPAWN_EGGは既存モブでも使用していますが、犬の個体を明確に示す用途から再利用します。復活オーブのTOTEM_OF_UNDYINGも、復活そのものを表すバニラの用途を優先して再利用します。新しいResource Packモデル参照は追加しません。
