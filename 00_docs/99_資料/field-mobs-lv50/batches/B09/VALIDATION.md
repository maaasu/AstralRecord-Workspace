# B09 検証結果と統合引継ぎ

基点`3444b74b2`。重いMaven/dotnet/npm build、実server/client、DB接続、本番操作は実行していない。中央登録、POM、properties、src/test、schema、spawner、管理表は編集していない。13個のB09所有ファイルをコミットして統合待ちにする。

## 完了した軽量検証

- `validate_b09.py --repo-root .`: 全866 YAMLを二重キー拒否で解析。863 top-level master ID、共有status106/tag134に重複なし。2Lv刻み4profile×2体、最大Lv50、mapping継承/list置換、各profileの全status、炎のMAGIC_ATTACK、共通素材drop継承、素材ID280/281、所有範囲、変更参照とmetadataを確認。結果は`STATIC_RESULTS.json`。2skillは中央未登録として明示。
- `check_plugin_resources.py --repo-root .`: 成功、361 log ID/981 player message ID。直接logger/message/raw particleや共有propertiesへの追加なし。
- JDK21.0.10、Paper1.21.11 API、既存workspaceのtarget/classesで追加Java3だけを`javac -encoding UTF-8 -proc:none -Xlint:all`。修正後成功、警告0。argfileと完全出力はTempの`astralrecord-b09-javac/javac.args`、`javac-output.txt`。既存classesの基点は保証できず、全体ソースbuildの代替ではない。
- Paperの`javap`でPARROT_SPAWN_EGG/SILVERFISH_SPAWN_EGG/RED_SANDSTONE/FEATHER/SLIME_BALL、Parrot.Variant.GRAYの存在を確認。
- 第1回独立Solレビュー: 液体がignorePassableBlocks=trueで無視されるAR-CODE-001を検出。明示検査を追加。
- 第2回独立Solレビュー: 点間隔より短い液体block角通過を見逃すAR-CODE-002を検出。有限DDAで通過blockを列挙する方式へ修正。修正箇所の限定確認で角通過/軸平行/負方向/ゼロ距離/有限終了を確認し、追加指摘なし。
- 一時Java診断で実際のprivate DDAを最小World/Block interface doubleから呼び、液体角通過の正逆、負座標、waterlogged、空の斜線/軸平行/負方向境界/ゼロ距離、垂直液体/下降、未読込chunkでblock読取0件の11ケース成功。Bukkit server/MockBukkitを起動せず、恒久テストへ追加していない。診断source/classは削除し、完全出力だけTempの`boundary-output.txt`へ保持。初回実行の不足依存net.kyori.examinationを既存local Maven cacheのkyori jar追加で解消。
- 正規レビュー記録`00_docs/99_資料/レビュー結果/[完了] 26-10-15 02：19：12 code-review.md`: 指摘2件をcode-fix updaterで修正済みに更新、validator成功。

既存データ契約内の追加、paramsなし。Plugin/API mapperと編集サイトの動的catalogが既存levels/ref/status形式を扱うため、共有ソース変更は不要。画面の反映は統合後。src/test/POM/許可設計入力/テスト方針の変更がないため、test traceability gateは対象外。Pluginテストから本番filebaseを読ませていない。

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 '00_docs/99_資料/field-mobs-lv50/batches/B09/validate_b09.py' --repo-root .
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 '.codex/skills/astralrecord-code/scripts/check_plugin_resources.py' --repo-root .
```

## 統合担当の残工程

1. manifestに従い中央でExecutorを一度ずつ生成/登録し、destroy listenerと依存service停止前のonDisableへhandleMobDestroyed/stopを接続する。EmberSlugは全個体で同じExecutorを共有する。Java supportも同時に取り込む。
2. 最新統合ツリーで全ID/変更参照、8個体のmaster読込、2skill登録、編集サイト動的表示、全体build/test、リベース後影響と最終Plugin版番号を確認する。
3. 正式なLv31〜50のPlayer装備/class/skilltreeと実TTK/耐久を確定する。現在の数値はB08の仮想基準を延長した暫定値で、既存代表R416.58では全DEFが完全防御になる。
4. 実client/Geyserで固定円30tick予告→10tick下降→一撃、固定帯30tick予告→80tick燃焼→消失を確認。円/帯外、壁、高低差、天井、液体角、chunk境界、WorldBorder、地形変化で避けられることとchunk loadが起きないことを確認する。
5. player/召喚pet、Shield/回避/無敵、主対象以外へ命中しないこと、最大4回と複数Slugの共通20tick一試行を確認する。通常AIは発火時に再開する。
6. 自然満了、帰還、target変更/死亡/logout/召喚解除、caster死亡/despawn/world変更、行動不能、例外、Plugin停止でtask/AI停止/重複Mapが残らないことを確認する。
7. メサ/火山系の実worldと座標を確認してから配置する。素材のrecipe/装備用途は別担当、売却の抽選/期待値/Gold sinkは統合後に確認する。

## 保持情報

branch `codex/field-mobs-lv50-b09`、worktree `E:\AstralRecord-Workspace\99_work\field-mobs-lv50-b09`は未マージの統合待ちとして保持。develop merge/push/deploy/POM版番号更新は親統合担当。`E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md`は統合担当単独所有のため未変更。分類はcommit後に`UNMERGED_WORKTREE`、未コミット差分なし。
