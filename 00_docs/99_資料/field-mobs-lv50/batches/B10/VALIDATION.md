# B10 検証結果

基点`3444b74b2`からB10所有範囲だけを追加。管理表・中央登録・共有設定・POM・既存drop・spawnerは編集していない。

| 確認 | 結果・証拠 |
|---|---|
| YAML解析・全体ID重複 | `validate_b10.py`成功。868 YAML/865 master ID、重複なし |
| 対象マスタ | 2種×4レベル、4素材、4参照を解決。status/tag・Mob icon・level継承・drop継承・manifest整合を確認 |
| 戦闘・経済事前検算 | `STATIC_RESULTS.json`。仮想TTK・通常近接耐久・売却期待値・超低確率比較を保存 |
| 限定Javaコンパイル | JDK21.0.10、`-proc:none -encoding UTF-8 -Xlint:all -implicit:none`。3 Javaのみ終了0・警告0、4 classを一時ディレクトリへ出力 |
| コンパイル証拠 | `JAVAC_RESULT.json`に対象SHA256・終了コード・完全出力（空）・対象範囲を保存。既存target/classesとローカルMavenキャッシュを利用し、全体buildは行っていない |
| 初回classpath警告 | 無関係なFAWE/Kotlin compiler JARのClass-Path manifestに由来する22件。対象外JARとsource JARを除き再実行し、最終警告0を確認 |
| Pluginリソース | `check_plugin_resources.py --repo-root <B10>`成功：361 log IDs / 981 player message IDs |
| 第1回独立レビュー | Sol、実装/範囲・予告・cleanup・既存戦闘API照合、指摘なし。正規レビュー記録は作成せず |
| 第2回独立レビュー | 別のSol、マスタ/報酬/継承/仕様・manifest照合とvalidator再実行、指摘なし。正規レビュー記録は作成せず |
| 恒久テスト・重いbuild | 未実行。個別Mob技は恒久テスト対象外。全体build/testは統合担当へ委任 |
| traceability | テストソース/POM/許可設計入力/テスト方針を変更していないため対象外 |

再実行にはPyYAMLを持つPythonで次を使う。本環境ではWindowsAppsのaliasではなく`C:/Users/kaede/AppData/Local/Python/pythoncore-3.14-64/python.exe`を利用した。

```powershell
python -X utf8 <task-root>/00_docs/99_資料/field-mobs-lv50/batches/B10/validate_b10.py --repo-root <task-root>
```

統合ツリーでは同コマンドに`--integrated`を加える。B10以外の編集範囲監査だけを省略し、YAML・全体ID・対象参照・対象profile検証は維持する。

## 残る統合・実戦受入

- manifestどおりの中央registry、destroy listener、onDisable接続。現時点では2技とも`registration_pending`。
- 最新統合ツリーでの実master読込、全体build/test、統合直前のPlugin版番号更新。
- 実server/client/Geyserのモデル・音量・起伏地形での予告視認性・範囲境界・壁・対象喪失・despawn/disable・複数個体の受入。
- Lv31以降の正式な装備/class/skilltreeアンカーと実TTK/耐久、密度・再出現を含む討伐頻度、幸運込み素材供給の確認。仮想値と現行代表構成での完全防御の制約はSPECへ記載済み。
- 実配置のworld/座標、管理表更新、developへの取り込みは統合担当。

作業branch/worktreeは統合待ちとして保持する。
