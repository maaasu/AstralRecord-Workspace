# B06 検証結果と統合後の受入項目

基点`e2058ade10a682118382e70aa01c7db758ac81c6`。重いMaven/dotnet/npm build、疑似サーバー、実server/client、DB/port、本番操作は実施していない。src/test・POM・共有基盤は変更していない。専用テストは本ディレクトリの読み取り専用`validate_b06.py`で、個別Mobの恒久JUnitは追加しない。

## 実行済み

| 検査 | 結果と限界 |
|---|---|
| `validate_b06.py --repo-root <B06 worktree>` | 全861 YAMLを二重キー拒否で解析。858 top-level master ID、共有status106/tag134の重複なし。変更参照item250/251とroot buff解決。8個体の最小/上限50/幅6/2刻み・成長・近接維持・素材35%×1の継承・metadata・編集範囲を確認。結果は`STATIC_RESULTS.json`に保存。中央登録は未解決2件として明示する。 |
| `check_plugin_resources.py --repo-root <B06 worktree>` | 成功。359 log ID、969 player message ID。共通ログ/メッセージ追加、直接logger/sendMessage、raw Particleはなし。 |
| 新規Executor2ファイルだけの`javac -encoding UTF-8 -proc:none -Xlint:all` | JDK21.0.10・Paper1.21.11 API・既存workspaceのtarget/classesを参照する限定コンパイルが成功、最終試行は警告0。既存classesの基点は保証できないため全体ソースbuildを代替しない。出力はTempの`astralrecord-b06-javac/javac-output.txt`、argfileは同ディレクトリ。 |
| 現行Paperの`javap org.bukkit.Material` | TURTLE_SCUTE/TURTLE_SPAWN_EGG/WOLF_SPAWN_EGG/WHITE_WOOL/HANGING_ROOTSの存在を確認。 |
| コミット候補分類/差分確認 | 所有範囲の新規12ファイルのみ。既存enemy/drop/table/spawner・中央/管理表・POM/propertiesに差分なし。staged diff --checkと文字化け検査は成功。 |
| 第1回独立Solレビュー | 対象7ファイルを読み取り専用でレビュー。明確な指摘なし、記録ファイルなし。根のray経路が未読込chunkを跨ぐ懸念は断定できない質問だったが、0.25m刻みで読込chunkを確認してからrayTraceするガードを追加して解決。 |
| 第2回独立Solレビュー | 新規12ファイルと関連戦闘/バフ契約を別の担当が読み取り専用で確認。明確な指摘なし、記録ファイルなし。専用validatorを独立して再実行しexit 0。仕様/manifest/検証結果の一致と未登録2件・装備曲線/配置保留の記載を確認。 |

初回限定javacの広すぎるclasspathにはFAWE/Kotlin compiler JARのmanifest上の存在しない付属JARと古いJetBrains annotationが含まれ、外部/ローカルclasspath起因の警告28件が出た。必要なPaper/Adventure/Kotlin stdlib等と現行annotations26.1.0に限定して再実行し警告0になった。ソース起因の警告はなかった。Oracle javapathとWindowsApps Pythonのlauncherは使えなかったため、実在するJDK/Python本体のパスで実行した。

## 検証コマンド

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 00_docs/99_資料/field-mobs-lv50/batches/B06/validate_b06.py --repo-root .
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root .
```

Validatorは専用バッチの実データを検証するfilebase toolであり、通常Pluginテストから本番filebaseを読ませるものではない。src/test/POM/許可設計入力/テスト方針の変更がないためPlugin test traceability gateは対象外。

## 静的に確認したキャンセル・終了

- 同一の管理個体がCOMBATで生存し、Plugin enabled、target ID同一、caster/target/world同一、対象がゲームプレイ対象であることを開始時と毎tickに確認する。canRunAi/canCastSkill/canAttackを確認し、狼はcanMoveも確認する。
- 根は固定円と一回の主対象判定で、短時間バフだけを保持する。miss/全吸収で実ダメージとshield damageがともに0なら減速を付けない。別casterによる同ID再付与を個体同一性で区別する。
- 狼は24tick予告と10歩上限、全幅・高さ・足場・chunk/border検査、単発ray hit、teleport成否、外部移動の中断がある。命中時はdamageの前にtaskを解除する。
- 正常終了/例外は同じcleanup経路を通り、task参照・AI停止を解除する。Rootの命中後はAI停止所有権を既に返しているため、後から始まったAI停止をcleanupで消さない。
- 破棄listener/Plugin stopとの接続コードはmanifestで要求しており、現段階の共有入口には未接続。ソースが持つcleanupだけで接続済みとは判定していない。

## 統合担当へ渡す確認

1. manifest通りに両Executorを生成・registry登録・既存destroy listenerへ追記・onDisableへstopを追加する。status/buffサービス破棄より先にstopする。
2. 全体で一度に1件だけMaven compile/testを行い、全Mob skill ID・root buff・8個体の読み込みと参照を確認する。
3. `SPEC.md`の現行代表装備と設計アンカーの乖離を確認し、正式なclass/装備/skilltree構成で8個体のTTK/耐久を再試算する。高倍率skillで完全防御を突破できると誤認しない。受入構成が決まるまで配置保留。
4. 許可後の実server/clientで根の輪外回避、命中時の1.2秒減速と攻撃可能、miss/防御0damage、ペットtargetの通常攻撃fallback、複数亀による非重複・旧caster死亡後に新buffが残ることを確認する。
5. 狼の横回避、追尾しない進路、hit一回、player/pet/無効account、壁角/柵/半block/段差/崖/水面/chunk境界/world border/teleport失敗での停止を確認する。
6. 両種の死亡/despawn/帰還/target消失・変更/world変更/行動不能/Plugin停止中にdamageを追加せず、taskとAI停止が残らないことを確認する。通常近接と複数個体の共存、素材抽選の実数、Gold sinkを含む売却効率を実測する。
7. 森のLv24〜30座標と雪原world/spawner根拠を確認してから配置を決定する。静的マスタに実座標はなく、B06では捏造・追加していない。

## Gitと残事項

限定ローカルcommit後、branch/worktreeを統合待ちで保持する。push/develop統合/rebase/version/deployは今回実施しない。中央管理表はユーザーの編集禁止範囲なので更新せず、manifestに状態を残した。
