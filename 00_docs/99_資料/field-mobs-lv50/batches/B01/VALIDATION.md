# B01 検証結果

対象worktree: `E:/AstralRecord-Worktrees/field-mobs-b01`、branch: `codex/field-mobs-b01`。共通基点e2058ade10a682118382e70aa01c7db758ac81c6。中央共有ファイル・既存drop・loot・spawner・管理表に変更なし。

## 軽い静的検証

実行Python: `C:/Users/kaede/AppData/Local/Python/bin/python.exe`（PyYAML6.0.3）。JDK21.0.10。WindowsAppsのpython aliasが実行できず、cache版PythonにはPyYAMLが無かったため、既存のPython3.14/PyYAMLを使用した。インストールや共有環境変更は行っていない。

`python -X utf8 00_docs/99_資料/field-mobs-lv50/batches/B01/validate_b01.py <worktree>` は読み取り専用マスタ検証で、実サーバーやPlugin JUnitではない。

```json
{
  "counts": {
    "yaml_files": 861,
    "master_ids": 1123,
    "changed_yaml": 5,
    "changed_references": 3,
    "level_profiles": 8,
    "manifest_executors": 2
  },
  "errors": [],
  "runtime_status": "implemented_unregistered_not_runtime_verified"
}
```

全40_filebase YAMLの解析とnamespace別のmaster ID重複、変更素材参照、最小level/最大50/幅6、通常近接、空params、日本語名・Lore、motif/progression、タグ/status、素材ID・確率・数量・幸運・表示・売却を確認。初回のcatalog category IDとstatus/tag IDの同名検出はvalidatorのnamespace区別不足で、collectionごとに区別して修正した。上記は修正後の実結果。Mob/Enemy/Item/Materialのスキーマ文書を再読し、未定義キーは加えていない。

一時のJDK JavaCompiler/JavacTask.parseヘルパーで変更2ソースの構文を解析（semantic compilationとPlugin依存解決はしていない）。

```text
Java syntax parse PASS: 2 source files; no semantic compilation performed.
Plugin resource validation passed: 359 log IDs, 969 player message IDs.
```

resource検証コマンド: `python -X utf8 .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <worktree>`。WorldBorder修正後にも構文/resource検査を再実行して同じ結果を確認。実行した検査に新規警告なし。

cached Paper1.21.11-R0.1-SNAPSHOTをjavapで読み、MaterialのGREEN_WOOL/TURTLE_SCUTE/WOLF_SPAWN_EGG/SILVERFISH_SPAWN_EGGと、BoundingBox.expand/shift/contains/rayTrace、VoxelShape.overlapsの実在を確認した。未使用素材iconの検索ではGREEN_WOOL/TURTLE_SCUTEが既存itemに無かった。同じ甲羅の良質版へ同一iconを再利用する理由はSPECに記載。

MobRepositoryのレベル部分Mapは再帰mergeされ、各levelのdrops.expだけの上書きで共通素材itemsを継承することを独立レビューで照合。MobSkillServiceはMob専用IDとtimingを解決し、CDを詠唱開始時から管理する。全技能IDとExecutor/登録manifestを照合済みだが、中央Registryには登録していない。

## 独立レビュー

第1回: Sol、read-only。AR-CODE-001 [低]「草走り突進が水平WorldBorderを跨ぐ」を確認。各衝突サンプルの移動先hitboxの水平対角2点をWorldBorder.isInsideで検査する修正を採用。仕様・該当シナリオも同期した。ほかに確定指摘なし。

修正後の正規記録: `[完了] 26-10-15 00：13：28 code-review.md`。shared update_review_record.pyで修正状態を更新し、validate_review_record.pyで形式を確認。通常記録保存先は今回のユーザー所有範囲指定に従ってB01内へ読み替えた。

第2回: 別Solによるread-onlyレビューを完了。AR-CODE-001の修正で各サンプルのhitboxがWorldBorder内に収まることを確認し、回帰・新たな確定指摘なし。未解決レビュー指摘なし。

## Gitと後続

13所有ファイルのcommit_candidate_auditを実施し、全件REVIEWの内容を確認して限定ステージ。git diff --cached --checkとstaged_mojibake_check.pyは成功。末尾空行3件だけを修正して再確認し、文字化けなし。管理表は委譲の編集禁止指定に従って変更していない。既存モブ・素材・共有ファイルは一切変更していないので、既存drop値はそのまま保全される。新規lootTableを付けず、既存高進行tier_2報酬をコピーしていない。

未実施: semantic compile、Maven/dotnet/npm build/test、中央登録後の全技能解決、実戦・クライアント確認、spawner配置。B01の専用TEST_SCENARIOS.csvに15件を明示し、全てnot_run_requires_registered_serverとした。個別コンテンツの恒久JUnitや疑似サーバー、依存追加は行っていない。今回のテストはマスタ静的validatorと後続実戦シナリオで引き継ぐため、Pluginテストトレーサビリティの対象パスは変更していない。

統合担当へ: REGISTRATION.jsonの2クラスを一度だけ登録し、共有の重い検証枠でPlugin Maven検証を直列実行する。配置world・実座標・密度は未確認で設定していない。実戦時は予告・命中・回避・TTK・耐久・複数個体・停止cleanupを確認する。
