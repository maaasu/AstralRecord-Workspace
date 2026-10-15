# E07 既存フィールド敵への通常素材追加

予約の正本は中央commit `2c419c1af8cff4ffdef63a8cf726f96a3408fe63` の `EXISTING_MATERIAL_ASSIGNMENTS.csv`、条件は同commitの `EXISTING_MATERIAL_BATCHES.md`。作業基点はローカルdevelopの `3444b74b24ed99f11fbb93d5e26bfe00ad7f2780`。対象2 Mobの基点データが中央commitと同一であることを検証する。中央資料は複製・編集せず、検証スクリプトから `git show` で参照する。

| Mob ID | 実効レベル | 素材ID / 表示名 | progression | icon |
|:--|:--|:--|--:|:--|
| `midgard_moss_shell` | 2 | `10a00312` 苔甲のかけら | 2 | `TURTLE_SCUTE` |
| `midgard_savanna_camel` | 7 / 8 | `10a00313` 砂色のラクダ毛 | 7 | `BROWN_WOOL` |

## 所有範囲・統合への引継ぎ

- `40_filebase/40.features.mob/enemy/v1.midgard_moss_shell.yml`
- `40_filebase/40.features.mob/enemy/v1.midgard_savanna_camel.yml`
- `40_filebase/10.features.item/10.material/v1.10a00312.midgard_moss_shell_material.yml`
- `40_filebase/10.features.item/10.material/v1.10a00313.midgard_savanna_camel_material.yml`
- 本 `batches/E07/` の根拠・検証資料

新素材とdrop接続は本バッチ内で完結し、他E/Bバッチへの依存はない。共有loot・schema・中央資料・装備・recipe・API・Web・Editorは変更しない。限定commit後、`codex/field-mobs-existing-material-e07` と `E:\AstralRecord-Workspace\99_work\field-mobs-existing-material-e07` を「検証済み・統合待ち」として保持する。最新developへの取り込みとID・参照の再検証は統合担当が直列で行う。

今回の指示に従い、`E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` と中央割当表は編集しない。統合担当に保持状況だけを返す。

## レベル継承・既存データの保持

Pluginの `MobRepository.mergeLevelProfile` / `mergeJsonObjects` はMapを再帰mergeし、配列を置換する。

モスシェルはrootの `drops.items` に1件追記する。ラクダはrootと明示配列を持つLv.8の配列末尾へ各1件追記し、Lv.7はrootを継承する。全実効レベルで新素材がちょうど1件あり、追加drop除去後のroot・全profile・実効レベルデータが基点と完全一致することを検証する。追加した6行のブロックだけを除去すると、既存Mobのバイト列も基点と完全一致する。既存dropの順序・率・量・フラグ、lootTable、exp、money、stats、AI、skill、レベル、コメント・文字コードを保持する。

モスシェルの既存 `10a00020`「苔甲羅の欠片」は60%のまま残る。ラクダの既存 `10a00043`「サバナの獣皮片」と `10a00050`「ラクダ守りの甲片」も率・量を維持する。中央予約の新素材は別IDとして追加し、既存素材の置換・統合は行わない。

## 値・表示・読込の根拠

中央条件どおり、通常素材は `COMMON`、設定率35%、個数1、`luckAffected: true`、`hidden: false`。追加期待数量は無補正で0.35個/撃破。`MobDropService` は直接dropを個別に抽選し、LUCKとDROP_RATE_INCREASEで補正して0〜100%へ制限する。既存dropと共通lootの設定はそのまま保持する。戦闘性能に関わる値を変更しないため、既存のTTKと耐久時間に影響はない。

素材のprogressionは既存Mobと同値、maxStackは64、saleValueは0、取引・売却禁止フラグはfalse。Loreは由来と外観だけを示す。用途は今回の担当外であり、素材実装と装備反映後に統合担当が用途なし一覧を再判定する。

全filebaseのicon使用状況を確認した。`TURTLE_SCUTE` はパラディンのスキルで使用済みだが、カメ型の甲羅から剥がれた甲片をそのもののMaterialで示すため再利用の例外とする。既存の苔甲羅の欠片の `MOSS_BLOCK` とも区別できる。`BROWN_WOOL` は既存icon未使用で、ラクダ由来の毛の束を示す。独自モデル・テクスチャは追加しない。

ローカルのPaper API `1.21.11-R0.1-SNAPSHOT` jar（`10_plugin/AstralRecord/.dev-server/integration-live-clone/libraries/io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT/paper-api-1.21.11-R0.1-SNAPSHOT.jar`）の `org/bukkit/Material.class` 内に、`TURTLE_SCUTE`・`BROWN_WOOL`・既存Mobの `TURTLE_SPAWN_EGG`・`CAMEL_SPAWN_EGG` が存在することを確認した。Resource Packの変更は不要。

APIが番号付き `10.material` を論理カテゴリ `material` として読み込み、Pluginがアイテム詳細を取得する。`40_filebase/config.yml` のitemディレクトリと `item:` / `loot_table:` 参照の既存契約内の追加である。Editorはfilebaseを動的列挙するため、新規YAMLは一覧へ反映される。読込・Editorソースの変更は不要。

## 静的検証

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '00_docs\99_資料\field-mobs-lv50\batches\E07\validate.py'
git diff --check
```

検証は成功。全864 YAML・853種のトップレベルIDを解析し、既存のカテゴリ間同名ID8種に変化がなく、新規2 IDの重複がないことを確認した。変更4 YAMLの重複キー、新素材の必須キー・型・値・名前・カテゴリ・ファイル名・日本語Lore・設計コメントを確認した。全実効Lv.2 / 7 / 8に新素材がちょうど1件あり、既存データとバイト列の完全保持、item・lootTable参照解決、所有範囲を検証した。詳細は `validation.json` に保存する。

GPT-6 Solの別担当2名による第1回・第2回独立レビューは、いずれも指摘なし・未確認質問なし。各担当が対象7ファイルを照合し、独自stdin Python検査で実効Lv.2 / 7 / 8の新素材、既存データとバイト列の完全保持、所有範囲、ID・参照・素材スキーマを確認した。指摘がないため正規レビュー記録は作成しない。

コミット候補監査は所有7ファイルだけを検出した。7パスを明示してステージし、ステージ済み差分・空白・文字化け検査を行ってから限定コミットする。

重いbuild、server・本番DB・port操作、push、rebase、develop統合、deployは実施しない。プラグイン版番号更新はfilebaseのみのため不要。
