# E06 既存フィールド敵への通常素材追加

予約の正本は中央commit `2c419c1af8cff4ffdef63a8cf726f96a3408fe63` の `EXISTING_MATERIAL_ASSIGNMENTS.csv`、条件は同commitの `EXISTING_MATERIAL_BATCHES.md`。作業基点はローカルdevelopの `3444b74b24ed99f11fbb93d5e26bfe00ad7f2780`。対象2 Mobの基点データが中央commitと同一であることを検証する。中央資料は複製・編集せず、検証スクリプトから `git show` で参照する。

| Mob ID | 実効レベル | 素材ID / 表示名 | progression | icon |
|:--|:--|:--|--:|:--|
| `midgard_forest_wolf` | 6 / 7 / 8 | `10a00310` 森狼の爪 | 6 | `FLINT` |
| `midgard_grassboar` | 1 / 2 | `10a00311` 小さな猪牙 | 1 | `BONE` |

## 所有範囲・統合への引継ぎ

- `40_filebase/40.features.mob/enemy/v1.midgard_forest_wolf.yml`
- `40_filebase/40.features.mob/enemy/v1.midgard_grassboar.yml`
- `40_filebase/10.features.item/10.material/v1.10a00310.midgard_forest_wolf_material.yml`
- `40_filebase/10.features.item/10.material/v1.10a00311.midgard_grassboar_material.yml`
- 本 `batches/E06/` の根拠・検証資料

新素材とdrop接続は本バッチ内で完結し、他E/Bバッチへの依存はない。共有loot・schema・中央資料・装備・recipe・API・Web・Editorは変更しない。限定commit後、`codex/field-mobs-existing-material-e06` と `E:\AstralRecord-Workspace\99_work\field-mobs-existing-material-e06` を「検証済み・統合待ち」として保持する。最新developへの取り込みとID・参照の再検証は統合担当が直列で行う。

今回の指示に従い、`E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` と中央割当表は編集しない。統合担当に保持状況だけを返す。

## レベル継承・既存データの保持

Pluginの `MobRepository.mergeLevelProfile` / `mergeJsonObjects` はMapを再帰mergeし、配列を置換する。APIの `WebBestiaryRepository` も同様にレベル別dropsを解決する。

森林狼はrootの `drops.items` と、明示配列を持つLv.7・8の配列末尾へ各1件追記する。Lv.6はrootを継承する。グラスボアはrootへ1件だけ追記する。Lv.1はrootを継承し、Lv.2はexpだけのoverrideなのでitemsとlootTableをrootから継承する。

全実効レベルで新素材がちょうど1件あり、追加drop除去後のroot・全profile・実効レベルデータが基点と完全一致することを検証する。さらに追加した6行のブロックだけを除去すると、既存Mobのバイト列が基点と完全一致する。既存dropの順序・率・量・フラグ、lootTable、exp、money、stats、AI、skill、レベル、コメント・文字コードを保持する。

森林狼は既存素材 `10a00038`「フォレストウルフの爪」も従来の3% / 4% / 5%で引き続き落とす。今回の `10a00310` は中央予約に従う別IDの追加素材であり、既存素材の置換や統合は行わない。

## 値・表示・読込の根拠

中央条件どおり、通常素材は `COMMON`、設定率35%、個数1、`luckAffected: true`、`hidden: false`。追加期待数量は無補正で0.35個/撃破。`MobDropService` は直接dropを個別に抽選し、LUCKとDROP_RATE_INCREASEで補正して0〜100%へ制限する。既存dropと共通lootの設定はそのまま保持する。

素材のprogressionは既存Mobと同値、maxStackは64、saleValueは0、取引・売却禁止フラグはfalse。Loreは由来と外観だけを示す。用途は今回の担当外であり、素材実装と装備反映後に統合担当が用途なし一覧を再判定する。

全filebaseのicon使用状況を確認した。`FLINT` は既存の同一動物の爪 `10a00038` で使われており、森林狼の爪という同じモチーフの表示を揃えるため再利用する。`BONE` は骨片素材で使われており、猪の牙も白い硬質の動物由来素材として骨素材の表示を共有する。両方とも素材モチーフに基づく再利用の例外とする。独自モデル・テクスチャは追加しない。

POMが指定するPaper API `1.21.11-R0.1-SNAPSHOT` のローカルjar `paper-api-1.21.11-R0.1-20260511.115010-91.jar` 内 `org/bukkit/Material.class` に、`FLINT`・`BONE`・既存Mobの `WOLF_SPAWN_EGG`・`PIG_SPAWN_EGG` が存在することを確認した。Resource Packの変更は不要。

Plugin `ItemRepository.kt` がicon・rarity・maxStack・saleValue・loreを読み込み、API `ItemRepository.cs` はmaterialを対象カテゴリとして扱う。`40_filebase/config.yml` のitemディレクトリと `item:` / `loot_table:` 参照の既存契約内の追加である。Editorの `MasterDataService.ListAsync` は `MasterDataPaths.Enumerate` でfilebaseを都度列挙するため、新規YAMLは一覧へ動的反映される。読込・Editorソースの変更は不要。

## 静的検証

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '00_docs\99_資料\field-mobs-lv50\batches\E06\validate.py'
git diff --check
```

検証は成功。全864 YAML・853種のトップレベルIDを解析し、既存のカテゴリ間同名ID8種に変化がなく、新規2 IDの重複がないことを確認した。変更4 YAMLの重複キー、新素材の必須キー・型・値・名前・カテゴリ・ファイル名・日本語Lore・設計コメントを確認した。全実効Lv.6 / 7 / 8とLv.1 / 2に新素材がちょうど1件あり、既存データの完全保持、item・lootTable参照解決、所有範囲を検証した。詳細は `validation.json` に保存する。

GPT-6 Solの別担当2名による第1回・第2回独立レビューは、ともに指摘なし・未確認質問なし。中央割当・schema・差分を照合し、各担当の独自stdin Python検査で全5レベルの実効drops・ID・参照・既存データ保持を確認した。第2回は追加4ブロックの除去によるバイト列の完全保持も独立に確認した。指摘がないため正規レビュー記録は作成しない。

コミット候補監査は所有7ファイルだけを検出した。7パスだけを明示してステージし、ステージ済み差分・`git diff --cached --check` と文字化け検査が成功した。

重いbuild、server・本番DB・port操作、push、rebase、develop統合、deployは実施しない。プラグイン版番号更新はfilebaseのみのため不要。
