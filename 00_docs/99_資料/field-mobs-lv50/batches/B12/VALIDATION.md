# B12 検証結果

基点 `3444b74b2` の専用作業ツリーでB12所有範囲だけを追加。管理表・中央登録・共有設定・POM・既存loot master・スポナーは変更していない。

| 確認 | 結果・証拠 |
|---|---|
| YAML解析・重複キー・全体ID | validate_b12.py成功。864 YAML / 861 master ID、重複なし |
| 対象マスター | 1種×4 profile、通常素材1種。status/tag、モデル/icon、レベル・drop継承、参照、manifest、所有パスを確認 |
| 共通報酬参照 | Lv15以上通常敵の既存tier2を参照。table→pool→itemと武器箱の間接参照も解決。固有素材は1種 |
| 事前試算 | STATIC_RESULTS.json。防御姿勢込み仮想TTK6.17〜6.60秒、針の防御後1hitは仮想HPの14.14〜14.27%、固有素材の基礎売却期待値189G |
| 限定Javaコンパイル | JDK21.0.10、-proc:none / UTF-8 / -Xlint:all / -implicit:none / 空sourcepath。対象Java1単位、終了0・警告0、class2個を許可された一時ディレクトリへ出力後に削除 |
| コンパイル証拠 | JAVAC_RESULT.jsonに最終ソースSHA256、対象範囲、完全出力（空）、class出力一覧を保存。既存target/classesとローカルMavenキャッシュを使用 |
| 初回ツールチェーン警告 | キャッシュの古いannotations13.0がPaperのContract.mutatesを解決できない6件。対象ライブラリを既存26.1.0に絞り最終警告0。プロジェクト依存は変更していない |
| Pluginリソース | check_plugin_resources.py成功、361 log IDs / 981 player message IDs |
| 独立レビュー | Sol二名が戦闘処理とデータ整合を確認。例外cleanup・共通loot参照・中央停止例外の隔離指示の3件を修正。独立再確認で3件解消・新たな高/中指摘なし、正規記録の3/3修正済みと最終STATIC_RESULTSの一致も確認 |
| 恒久テスト・重いbuild | 未実行。個別Mob技は恒久テスト対象外。全体build/testは統合担当へ委任 |
| Traceability | テストソース/POM/許可設計入力/テスト方針を変更していないため対象外 |

マスターの再検証はPyYAMLのあるPythonで実行する。本環境では `C:/Users/kaede/AppData/Local/Python/pythoncore-3.14-64/python.exe` を使用した。

```powershell
python -X utf8 <task-root>/00_docs/99_資料/field-mobs-lv50/batches/B12/validate_b12.py --repo-root <task-root>
```

統合ツリーでは `--integrated` を加えるとB12以外の差分所有監査だけを省略する。YAML・全体ID・対象profile・直接/間接参照検証は維持する。

残る受入は中央registry/destroy/onDisable接続、実master読込、最新ツリーの全体build/testと版番号、実world/座標の配置。実server/client/Geyserでモデル姿勢・輪の視認性・音量・起伏・壁・境界・対象喪失・despawn/disable・複数個体と密度を確認する。正式Lv31以降の育成アンカーと実TTK/耐久、幸運込み素材供給、編集サイトの動的表示は未確認。比較用の仮想値を本番受入済みと扱わない。

ブランチ／作業ツリーは統合待ちとして保持する。管理表更新、develop統合、版番号は本担当では実施しない。
