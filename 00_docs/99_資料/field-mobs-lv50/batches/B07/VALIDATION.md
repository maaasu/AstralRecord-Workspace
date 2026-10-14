# B07 検証と統合後の受入

基点`3444b74b2`。重いMaven/dotnet/npm build、疑似サーバー、実server/client、DB接続、port、本番操作は実施していない。共有登録・POM・src/test・properties・スキーマ・spawnerも変更していない。新規Mob専用の恒久JUnitは追加せず、バッチのfilebase validatorと限定コンパイルを使う。

## 実行済み

| 検査 | 結果と限界 |
|---|---|
| `validate_b07.py --repo-root .` | 全867 YAMLを二重キー拒否で解析。864 top-level master ID、status106/tag134に重複なし。8個体の継承、全6status、2Lv刻み/幅6/最大50、通常近接2m/36tick、素材260/261/262参照、drop継承、metadataと所有範囲を確認。`STATIC_RESULTS.json`に保存。未登録skill2件も出力し、registry解決済みと扱わない。 |
| `check_plugin_resources.py --repo-root .` | 成功。361 log ID/981 player message ID。新規logger/sendMessage、raw particle、共通properties変更なし。 |
| Executor2ファイルだけの`javac -encoding UTF-8 -proc:none -Xlint:all` | JDK21.0.10、Paper1.21.11 APIと既存workspaceのtarget/classesを参照する限定コンパイルが成功、警告0。argfileと完全な出力はTempの`astralrecord-b07-javac/javac.args`と`javac-output.txt`。既存classesの基点は保証できず、全体ソースbuildの代替ではない。 |
| 第1回独立Solレビュー | 2 Executor・5 YAML・manifest・validatorを既存MobSkill/Damage/Condition/Particleと照合。明確な指摘なし、正規レビュー記録なし。レビュー担当側のWindowsApps Python起動不可を把握し、validatorは親が実在するPython本体で実行した。 |
| 第2回独立Solレビュー | 全12新規ファイルと既存AI/対象/状態/ダメージ契約を確認。明確な指摘なし、正規レビュー記録なし。独立したvalidator再実行もexit 0。STATIC_RESULTSの生成前11パスを指摘したため再生成し、12パスへ更新。 |
| Paper Material確認 | `javap org.bukkit.Material`でBAT_SPAWN_EGG/PIG_SPAWN_EGG/FEATHER/GLOWSTONE_DUST/POINTED_DRIPSTONEの存在を確認。 |

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 00_docs/99_資料/field-mobs-lv50/batches/B07/validate_b07.py --repo-root .
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -X utf8 .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root .
```

Validatorは個別filebase/tool検証であり、通常Pluginテストから本番filebaseを読ませていない。src/test/POM/許可設計入力/テスト方針の変更がないためtraceability gateは対象外。ファイルベース契約は既存の追加だけで、MobSkill object timingにparamsを増やしていない。既存API/YAML mapper、編集サイトの動的catalogが新規ファイル/levels/refを読む構造を確認し、編集サイトを実行して画面反映を確認する工程は統合待ちとする。schema/共通ツールの変更は不要。

## 静的に確認した境界と終了

- 共通ゲームプレイ対象だけを主対象とし、caster/target/originの同一world、COMBAT、生存、管理個体の同一性、targetId、Plugin enabled、行動可能を開始時/毎tickに確認する。二重castは拒否する。
- 氷粉は32tick・固定1.8m円・1回の主対象判定。円の外接領域とray経路のchunk/border/高さ、実衝突面を確認する。対象が円外や壁の後ろへ移動すれば命中しない。
- 霜突進は32tick+最大10歩で終了し、全実体の衝突shape/足場/chunk/border/高さ、接触hitbox、遮蔽物、teleport成否を検査する。命中で1回だけdamageして終了する。
- 追加CC/buff/DoT/強制移動はない。状態異常の永久拘束は作らず、共通通常ノックバックと多敵DPSは既存戦闘/配置の受入範囲に残す。
- 命中処理より先にMapから実行を外し、taskをcancel、AI停止を解除する。破棄listener/stop/例外でも同cleanupを使う。現段階で中央listener/onDisableには未接続。

## 統合後の受入

1. manifestの登録・destroy listener・onDisableのstopを接続し、2 skill IDと8個体/3素材のmaster読込を確認する。依存サービス停止より先にstopを呼ぶ。
2. 他batch取り込み後の全体ID/変更参照を再検証し、統合担当が一度に1件だけ全体build/testする。版番号は最終統合時に更新する。
3. 正式なLv31+のclass/装備/skilltree構成でTTK/耐久を再試算する。現行代表+30装備のR416.58では本バッチの全DEFが完全防御となる。配置と戦闘受入を保留する。
4. 実clientで六方円/猪の進路/狼の進路の判別、1.6秒予告、円外移動/横回避、主対象1体だけのhit、miss/Shield/氷耐性、小型/大型pet、creative/spectator/無効accountを確認する。
5. 壁角/柵/半block/段差/崖/水面/地面破壊/chunk境界/WorldBorder/teleport失敗/外部移動で壁抜け・chunk load・不正命中がないことを確認する。
6. 対象消失/変更、ログアウト、pet召喚解除、帰還、死亡/despawn、world変更、行動不能、Plugin停止を予告中と突進中に行い、追加damage/task/AI停止が残らないことを確認する。
7. 雪原三種の複数個体同時発動でも歩行/スキル操作が可能なこと、通常ノックバックと密度が過剰にならないこと、素材独立抽選/売却期待値とGold sinkを実測する。
8. 実在する雪原world/配置座標を確認してからspawnerを作成する。本バッチはworld名・座標を捏造していない。

## Git

所有ファイルだけをローカルcommitし、branch/worktreeは統合待ちで保持する。develop merge/push/deploy/POM版番号更新は親の統合工程。B07の基点は既存batchの共通基点と異なるため、manifestと最終報告で明示する。

commit前の候補分類は所有12ファイルのみ。staged diff/文字化け検査を通してcommitする。worktree管理表`E:/AstralRecord-Worktrees/WORKTREE_MANAGEMENT.md`は生成スクリプトのdry-run `--write-management`で再生成し、削除を実行せず統合待ちのbranch/worktreeを保持する。
