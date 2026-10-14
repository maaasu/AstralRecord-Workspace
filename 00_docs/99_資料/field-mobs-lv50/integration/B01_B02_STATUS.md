# B01/B02中央統合結果

B02原commit7583681f7365d4271900b5ac65195f826d012724をb42a900e3へ、B01原commit0c765ea833600f57785166f84d43b034d5c4b962を0df467033へcherry-pick。所有範囲を確認し中央/他batch変更がない12/13fileを受入。親の指示による統合専用branch内の取込みで、develop/pushはなし。

AstralRecord.javaが4Executorを各1回構築・保持・登録。同じ参照を既存destroy listenerへ合成し、onDisableからMob一括破棄とScheduler一括cancelより先にstop。既存listenerのcallbackと順番は維持。B01の2Executorは既存finishを使う公開clearCasterState/stopを追加し、既存PluginDisableEventからもstopへ委譲。重複cleanupはactions.remove(instanceId,this)とsnapshot走査で無害。B02の2Executorはmanifest通り既存公開メソッドを接続。

## 完了した軽い検証

C:/Users/kaede/AppData/Local/Python/bin/python.exe -X utf8 integration/validate_b01_b02.py <integration-root>:

```json
{"yaml_files":865,"namespace_ids":1127,"registered_executors":4,"level_profiles":16,"materials":5,"existing_master_and_spawn_preserved":true,"result":"PASS","limits":"static source binding only; no semantic compile, runtime registry load, Maven or server"}
```

同PythonでB01/validate_b01.pyを再実行: YAML865、ID1127、B01変更YAML5、素材参照3、level profiles8、executors2、errorsなし。出力のruntime_statusは元manifestの未登録時点を保持する履歴値であり、現在の中央source登録状況は上の統合結果を参照。

check_plugin_resources.py --repo-root <integration-root>:

```text
Plugin resource validation passed: 359 log IDs, 969 player message IDs.
```

一時JDK JavaCompiler/JavacTask.parseでAstralRecord.javaと4Executorを構文解析（依存/type/semantic compileなし）:

```text
PASS: syntax parsed 5 Java sources; no semantic compilation
```

恒久JUnit、Maven、疑似serverを使っていない。トレーサビリティ対象のPOM/test/許可設計入力/テスト方針に差分なしのためtraceabilityゲートは省略。

## 素材確定値と根拠

NEW_MATERIALS.csvのrate_final/amount_final/saleValue_final/validation_stateへB01/B02実装値を追加。chance_draftは初期設計を示す履歴列で、実装値はfinal列を優先。

- 10a00200: 40%×1、売値200。Lv15グラスボアの40%例に合わせた通常素材。
- 10a00201: 45%×1、売値200。ヤブガニの長めTTK約6.8秒と既存森オオカミ45〜55%を考慮。
- 10a00202: 6%×1、売値380。既存希少3〜5%を参考に、耐久増/個数1に合わせ初期10%から抑制。
- 10a00210/211: 各35%×1、売値120/140。Lv15グラスボア毛皮と同率、通常COMMON素材の供給/期待売却42/49を抑える。

率差は意図的なので一律化しない。既存lootTable/素材確率/数量は変更していない。TURTLE_SCUTEは独立作業のB01甲羅/B02ウロコで重なるが双方が自然なモチーフとして選択した。クラフト用途は装備担当が所有。現在5素材はsaleValueで売却可能であり、装備消費先未実装と売却用途を区別する。

## スポーンと未検証

既存eriva_camouflage_zombie Lv22 spawnerはregionエリヴァ大草原、radius32、100tick、maxAlive8/maxNearby18/spawnPerPlayer1。既存world catalogは存在するが、そのspawnerのruntime設置world/座標と実際の配置密度は未確認。既存spawnerも新規spawnerも変更せず、world/座標捏造・追加密度の発生を避けて配置確認待ち。生息想定と既存参照は各manifestに保持。

未実施: Plugin依存解決/semantic compile、Maven test、実行時Registry/MobRepositoryロード、実server/clientの演出/衝突/target/TTK/耐久/drop/停止、実配置。重いbuild枠はequipment-scalar-random担当が使用中のため親の枠解放通知を待つ。API/Web/Editorの他担当worktreeは未編集。

独立レビュー第1回・別担当第2回とも中央登録/lifecycle差分に重大・中指摘なし。constructorと公開cleanup契約の型整合、既存listener保持、初期化前null guard、stop順を静的照合。semantic compile/runtimeは未検証。
