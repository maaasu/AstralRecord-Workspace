# E05 既存フィールド雑魚の通常素材追加

予約の正本は中央commit `2c419c1af8cff4ffdef63a8cf726f96a3408fe63` の `EXISTING_MATERIAL_ASSIGNMENTS.csv`、条件は同commitの `EXISTING_MATERIAL_BATCHES.md`。作業ツリーはローカルdevelopの `3444b74b24ed99f11fbb93d5e26bfe00ad7f2780` から作成した。対象2 Mobが中央commitと同じことを開始時に確認した。中央資料は複製・編集せず、検証スクリプトから `git show` で予約を参照する。

| Mob ID | 実効レベル | 素材ID / 表示名 | progression | icon |
|:--|:--|:--|--:|:--|
| `midgard_clay_guard` | 3 | `10a00308` 粘土のかけら | 3 | `CLAY_BALL` |
| `midgard_forest_spider` | 7 / 8 | `10a00309` 森グモの脚 | 7 | `DRIED_KELP` |

## 所有範囲と引継ぎ

- `40_filebase/40.features.mob/enemy/v1.midgard_clay_guard.yml`
- `40_filebase/40.features.mob/enemy/v1.midgard_forest_spider.yml`
- `40_filebase/10.features.item/10.material/v1.10a00308.midgard_clay_guard_material.yml`
- `40_filebase/10.features.item/10.material/v1.10a00309.midgard_forest_spider_material.yml`
- 本 `batches/E05/` の根拠・検証資料

新素材の参照は本バッチ内で完結し、他E/Bバッチへの依存はない。共有loot・schema・中央資料・装備・recipe・API・Web・Editorは変更しない。限定commitを返し、`codex/field-mobs-existing-material-e05` と `E:\AstralRecord-Workspace\99_work\field-mobs-existing-material-e05` を保持する。最新developへの取り込みとID・参照の再検証は統合担当が直列で実施する。

管理表は統合担当のみが更新するという今回の指示を優先する。`E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` と中央の割当表は編集しない。統合担当には、E05のブランチ・作業ツリー・限定commitを「検証済み、統合待ち」として登録する変更希望を返す。

## レベル継承と既存データの保持

クレイガードはroot `drops.items` の末尾へ新素材1件を追加する。フォレストスパイダーはrootに加え、明示items配列を持つLv.8の末尾へ同じ新素材1件を追加する。Lv.7はrootを継承するため、level profile側へ重複追加しない。

`MobRepository.mergeLevelProfile` / `mergeJsonObjects` はMapを再帰的にmergeし、配列を置換する。この動作に従い、root・全profile・全実効レベルから追加dropを除去した構造が基点と完全一致することを機械比較する。既存drop、lootTable、exp、money、stats、AI、skill、装備、レベルを保持する。

## 値・表示・読込の根拠

通常素材は中央指定の `COMMON`、設定率35%、個数1、`luckAffected: true`、`hidden: false`。追加期待数量は無補正で0.35個/撃破。`MobDropService` は直接dropごとに独立抽選し、LUCKとDROP_RATE_INCREASEを適用して0〜100%へ制限する。既存drop・共通lootの抽選枠は減らさない。

素材のprogressionは既存Mobと同値、maxStackは64、saleValueは0、取引・売却禁止フラグはfalse。Loreは由来・外観に留め、未実装の製作先を断言しない。素材の利用先は今回の担当外であり、素材実装と装備反映後に統合担当が用途なし一覧を再判定する。

既存全filebaseのicon使用状況を確認した。`CLAY_BALL` はクレイガードの土塊と関連クエストに使用済みだが、今回も粘土そのものなのでモチーフ上自然な再利用として採用する。`DRIED_KELP` は基点で未使用。森グモの脚の黒褐色・細長い輪郭を表す。独自モデル・テクスチャは追加しない。

POMで指定されたPaper API `1.21.11-R0.1-SNAPSHOT` のローカルjar `paper-api-1.21.11-R0.1-20260511.115010-91.jar` 内 `org/bukkit/Material.class` に、両素材iconと既存Mobの `HUSK_SPAWN_EGG` / `SPIDER_SPAWN_EGG` が存在することを確認した。Resource Pack変更は不要。

Plugin `ItemRepository` が共通のicon・rarity・maxStack・saleValue・loreを読み込み、API `ItemRepository` もmaterialカテゴリを扱う。`40_filebase/config.yml` のitemディレクトリと `item:` / `loot_table:` 参照の既存契約内で動作する追加なので、読込処理・Editorのソース変更は不要。

Editorの `MasterDataService.ListAsync` は `MasterDataPaths.Enumerate` でfilebaseを都度再帰列挙しており、新規YAMLはデータ追加として一覧に動的反映される。実行中のEditor・serverを起動/変更する検証は行わない。

## 静的検証

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '00_docs\99_資料\field-mobs-lv50\batches\E05\validate.py'
git diff --check
```

検証は成功。全864 YAML・853種のトップレベルIDを解析し、基点から存在するカテゴリ間の同名ID8種が増減していないこと、追加ID2件に重複がないことを確認した。変更4 YAMLについて重複キー・素材の必須キー/型/値/名前/カテゴリ/ファイル名/日本語表示/設計コメントを確認した。全実効Lv.3 / Lv.7 / Lv.8に追加dropがちょうど1件存在し、全既存データの保持とitem/lootTable参照の解決を確認した。詳細は `validation.json` に保存する。

別途、既存2 MobのYAMLから追加dropの文字列だけを除去すると、基点のバイト列と完全一致することを確認した。既存内容・コメント・文字コードを変更していない。

GPT-6 Solの読み取り専用担当2名による第1回・第2回の独立レビューは、いずれも指摘なし・未解決質問なし。各担当は中央割当・schema・読込/抽選処理・差分を照合し、独自stdinのPython検査でrootと実効Lv.3 / Lv.7 / Lv.8の保持を確認した。両担当とも `git diff --check` を通過した。指摘がないため正規レビュー記録は作成しない。

コミット候補監査は所有7ファイルだけを検出した。ステージはその7パスだけを明示する。

重いbuild、server・本番DB・port操作、push、rebase、develop統合、deployは実施しない。プラグイン版番号更新はfilebaseのみのため不要。
