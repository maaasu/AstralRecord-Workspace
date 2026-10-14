# E04 既存フィールド雑魚の通常素材追加

予約の正本は中央commit `2c419c1af8cff4ffdef63a8cf726f96a3408fe63` の `EXISTING_MATERIAL_ASSIGNMENTS.csv`、実装条件は同commitの `EXISTING_MATERIAL_BATCHES.md`。作業ツリーはローカルdevelopの `3444b74b24ed99f11fbb93d5e26bfe00ad7f2780` から作成した。対象2 Mobは中央commitと同一であることを開始時の差分確認で検証した。中央資料の複製・編集はせず、検証スクリプトは `git show` で予約を読み込む。

| Mob ID | 実効レベル | 素材ID / 表示名 | progression | icon |
|:--|--:|:--|--:|:--|
| `eriva_underbrush_spider` | 15 | `10a00306` 森グモの糸 | 15 | `STRING` |
| `maril_sil_dead_hero` | 10 | `10a00307` 古びた腕輪 | 10 | `IRON_CHAIN` |

両Mobは `levels` を持たない従来形式。rootの `drops.items` 末尾へ新素材を1件ずつ追加する。追加項目を除去したroot・全実効レベルが基点と完全一致することを機械比較し、既存drop、lootTable、exp、money、stats、AI、skill、装備、レベルを維持する。

## 所有範囲と引継ぎ

- `40_filebase/40.features.mob/enemy/v1.eriva_underbrush_spider.yml`
- `40_filebase/40.features.mob/enemy/v1.maril_sil_dead_hero.yml`
- `40_filebase/10.features.item/10.material/v1.10a00306.eriva_underbrush_spider_material.yml`
- `40_filebase/10.features.item/10.material/v1.10a00307.maril_sil_dead_hero_material.yml`
- 本 `batches/E04/` の根拠・検証資料

この2素材の参照は本バッチ内で完結し、他E/Bバッチへの依存はない。共有loot・schema・中央資料・装備・recipe・API・Web・Editorは変更しない。限定commitを親へ返し、`codex/field-mobs-existing-material-e04` と `E:\AstralRecord-Workspace\99_work\field-mobs-existing-material-e04` を保持する。中央取り込みと最新developに対するID・参照の再検証は統合担当が直列で実施する。

## 値と表示の根拠

指定の通常素材は `COMMON`、設定率35%、1個、`luckAffected: true`、`hidden: false`。無補正時の追加期待数量は0.35個/撃破。`MobDropService`は各直接dropを独立抽選し、幸運とドロップ増加率を適用して0〜100%へ制限する。共通lootの抽選枠や既存dropを減らさない。

素材のprogressionは既存Mobの設計コメントと同値。maxStackは64、saleValueは0、取引・売却禁止フラグはfalseとし、金策収入は追加しない。Loreは由来・外観のみを述べ、未実装の製作先を断言しない。利用先の作成は今回の担当外であり、素材実装と装備反映の後に統合担当が用途なし一覧を再判定する。

全filebaseの既存iconを確認した。`STRING`はスケルトンの弓弦と始源遺跡の弦糸に使用済みだが、クモが吐く糸そのものを表すため自然な再利用として採用した。`IRON_CHAIN`はノクスチャームに使用済みだが、輪をつないだ金属製の腕輪を表すため採用した。未定義の色・モデル・独自テクスチャは追加しない。

POMのPaper API `1.21.11-R0.1-SNAPSHOT` とローカルjar `paper-api-1.21.11-R0.1-20260511.115010-91.jar` の `org/bukkit/Material.class` で、両素材iconと既存2 Mobの対応spawn eggが存在することを確認した。Resource Pack変更は不要。

Plugin `ItemRepository`が共通のsaleValue/maxStack/rarity/icon/lore等を読み込み、API `ItemRepository`はmaterialカテゴリに対応している。`40_filebase/config.yml`がitemディレクトリと`item:`・`loot_table:`参照を解決する。既存の素材スキーマ内のデータ追加なので、読込契約・Editorの変更は不要。

## 静的検証

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '00_docs\99_資料\field-mobs-lv50\batches\E04\validate.py'
git diff --check
```

PyYAMLで全filebase YAMLを解析し、Git archiveから取得する実際の基点ツリーと全IDを比較する。変更4 YAMLは重複キー・素材の必須キー/型/値/カテゴリ/ファイル名/日本語表示/設計コメントを検査する。追加IDが基点では未使用で現在は各1件、実効dropが末尾に1件、既存直接dropとlootTableの参照が解決し、追加drop以外の既存データが完全一致することを確認する。結果は同dirの `validation.json` に保存する。

重いbuild、server/本番DB/port操作、push、rebase、develop統合、deployは実施しない。プラグイン版番号更新はfilebaseのみのため不要。

## 検証と独立レビューの結果

静的検証は全864 YAML・853種のトップレベルIDを解析して成功した。skill/buffまたはfishing_area/worldをまたぐ同名ID8種は基点から存在し、今回の追加で増減はない。追加IDの重複はなく、2 Mobのroot・実効Lv.15/Lv.10、参照解決、既存全データ保持も成功した。

GPT-6 Solの読み取り専用担当2名が独立にレビューした。第1回は中央割当・対象スキーマ・読込/抽選処理・表示/進行度・差分を確認し、指摘なし、未解決質問なし。第2回は検証スクリプトと記録を確認し、独自のPython stdin検査で追加dropを除いた既存構造の完全一致を再確認して、指摘なし、未解決質問なし。どちらも `git diff --check` を通過した。指摘がないため正規レビュー記録は作成しない。

コミット候補監査は所有7ファイルだけを検出した。作業ツリー管理一覧はGit運用スキルの必須手順に従い、外部の `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を更新する。リポジトリ内の共有資料は編集しない。
