# 統合後の品質ゲートと隔離データ受入

検証対象は `codex/field-equipment-final-integration` の新統合内容。最終コード・定義修正のHEADは `35bbdf3475dc4c65ab0ca3cf1a994990f6b54145`。初回API検証の `b046beab`、Webテスト追加後の `7928471b`、Editor定義修正後のHEADについて、各検証プロジェクトのGit treeと57装備・9セット・2Shopの入力SHA256を比較し、再検証しなかった範囲の同一性を確認した。旧統合 `989d32b7` の結果は今回の成功件数に代用していない。

## 結果

| 対象 | 実行 | 成功 | 失敗/skip | 警告 |
|---|---|--:|:--|:--|
| Plugin | compile、clean test | 1022 | 0/0 | 既存serialVersionUID 1件 |
| Plugin設計トレーサビリティ | 1018メソッド/225ソース | PASS | 0 | なし |
| API | build、通常の全test | 875表示 | 0/0 | 既存依存advisory 3種（build集計10件） |
| APIローカル再確認 | SQL Server categoryを明示除外、受入source除去後に再compile | 829 | 0/0 | 同上 |
| Web | build、全test（恒久random応答回帰含む） | 160 | 0/0 | なし |
| Editor Server最終 | build、全test（恒久rune wrapper回帰含む） | 304 | 0/0 | なし |
| Editor Client | npm ci、lint、全test、build | 270/35ファイル | 0/0 | なし |
| 最終Filebase静的検証 | 全1004 YAML、固定成果、旧敵44実効level、57装備・9セット・2Shop | PASS | error0/pending0 | なし |
| 実57装備fixture | API 2 + Plugin 5 + Editor 1の一時受入 | 8 | 0/0（最終） | 同じ既存API依存advisory |

APIの875表示には、opt-in flag未指定で本体を早期returnするSQL Server用46ケースが含まれる。SQL Serverを実行した件数ではない。後段の829件はそれらを明示除外した件数である。875と829を合算しない。

## 実データを使った受入範囲

元の実YAML 57装備・9セット・2Shopを専有Tempへコピーし、固定HEADと各68ファイルのSHA256を記録した。期待ID `20a00400`〜`20a00456`、9セット、120 random定義、42 zeroChance定義、全57対価/配置を固定した。

1. API実 `MasterDataSeeder` → SQLiteインメモリMasterDataDB → `ItemRepository`/`SetEffectRepository` → API DTOを読込。スカラーのrandom定義とルーン枠wrapperの正規化を確認した。
2. Plugin実 `ItemRepository` parser → `ItemService.createLocalEquipmentInstance` で57個体を生成。全207ステータスロールの順序/種類/固定min=max/範囲/刻みを確認し、zeroChanceの閾値直前・閾値・非0端点を全42定義で検査した。
3. 実 `PlayerStateSnapshot` が作ったpayloadをAPIへ渡し、実 `PlayerStateSnapshotRepository.SaveAsync` で57個体をSQLiteへ保存。同じ要求のreplay後も57個体を保持。別EF contextの実 `EquipmentRepository`/`EquipmentService.MapToResponse` を経由し、Plugin実decoderで全207ロール、個体ID・itemID・accountID・強化/ルーン枠/耐久を比較した。再抽選は発生しなかった。DB時刻の更新は許容し、個体値の不変と分けた。
4. 9セットを実 `StatusService.applySetEffectBonus` に1/2/3/4部位で渡し、2/4閾値と累積効果を確認した。
5. 2Shopを実 `ShopRepository.loadSnapshot` と `ShopService` に渡し、57新商品を固定manifestのGold/素材/数量/page/slotと照合した。既存の商品を含む全slotの重複も確認した。
6. 専有Editor workspaceコピーの実 `MasterDataService.ReadAsync` → validation → codec → `SaveAsync` → readを57装備に実行。random数値/未知root key/コメント本文の保持を確認した。構造変更時のコメント位置・空白整形は既存仕様であり、その不変を保証する検査ではない。

一時受入sourceは確認後にリポジトリから除去した。Editor一時sourceはTemp限定MSBuild取込を解除し、304件の恒久testだけで再buildした。内容IDを固定する一時テストを恒久suiteや隠しテンプレートとして追加していない。恒久回帰はWebのwire JSON→DTO→表示と、Editorの定義メタデータ→validationを使う2件である。

## 修正した不整合

Editor実受入で `rune.maxSlots: {random: "0~1"}` が定義表のIntegerだけの型で拒否された。APIは既存 `FlexibleStringConverter` でwrapperを範囲文字列へ正規化し、Pluginへ渡すため、API/Pluginの処理変更は不要だった。装備定義表をInteger/Objectと子random Stringへ訂正し、未対応のrandom min/max object記述を除いた。汎用回帰を追加した `35bbdf347` を取り込み、実57件と全Editorテストを再実行した。

一時Editorテストは初めコメントの空白を過剰に固定していた。既存構造変換の仕様に合わせてコメント本文を比較するassertへ訂正し、元YAMLコピーに戻して57件すべて再実行した。ソース保存処理を緩和する変更は行っていない。

`validate_final_integration.py` は、今回レビューした定義書とEditor汎用テストの正確なblobだけを許可し、任意の実装・契約変更や未commit変更は引き続き失敗させる。`FINAL_VALIDATION.json` は「全契約無変更」とは記さず、この2変更と実装不変を区別する。

## 警告・証拠

APIの既存警告は System.Security.Cryptography.Pkcs 9.0.13のNU1902/NU1903とSQLitePCLRaw.lib.e_sqlite3 2.1.11のNU1903。Pluginは `GuideCompletionRewardRepository.RewardUnavailableException` のserialVersionUID不足。依存定義と当該ソースは基点 `989d32b7` と同じblobで、今回の作業起因警告はない。他担当が使う主checkoutでの追加buildは実行していない。

詳細なTRX件数/時刻、プロジェクトtree、各入力・出力・完全ログのSHA256は `HEAVY_VALIDATION.json`。完全ログと一時sourceは `C:\Users\kaede\AppData\Local\Temp\field-equipment-heavy-gates-b046` およびJSON記載の専有Tempに保持した。実行環境は.NET 10.0.302 / Java 21.0.10 / Node 24.18.0 / npm 11.16.0。build/testは主担当だけが順次実行した。

## 戦闘・供給と未実施

`heavy-balance/REPORT.md` は実Mob 8profile×4系列×7武器×3強化×4条件=2688シナリオ、全76取得商品と28武器+防具セットの負担を再計算する。Lv25通常は実Lv24の代理、Lv25強枠はBOSSで、同Lv通常/強敵比較とは区別する。

+0/+15の更新は系列順。Lv30+30の無刻印期待のみ旧エリヴァがラピスより約0.6%速い14条件が残る。アストラ+30ソードの最適刻印極値はR5007/通常敵TTK1.092秒、Lv30アンカーの150.9%。これを実戦最短値や通常の取得期待と扱わない。強化投資の保護と刻印極値のリスクを両方記録し、共通刻印の制限をこの統合へ追加しなかった。

代表ソード+ガード一式の主取得は、Lv20で採集177破壊/414純秒、Lv25で134破壊/381.6純秒と期待98.17討伐、Lv30で456破壊/1094.4純秒と期待301.43討伐。Astra石ころは鉱石と同時に出るため採集を二重計上しない。速度4の道具取得・移動/再出現・Gold時給は別。進化1回、強化試行費、刻印/ルーン入手費も区別する。最大単品の素材+出力は14枠、基礎BAG36枠以内。

実サーバーのAI/skill/Shield/間隔/移動/属性弱点、100戦測定、HTTP/購入GUI/再ログイン、SQL Server固有の保存挙動は未実施。新規spawnのworld/channel/region/座標・密度が不足し、HABITAT_PLANを実配置へ変換していない。develop反映・push・配備・実サーバー/DB/port操作も未実施。

最終横断レビューで未追跡実装の検出漏れと再実行例の旧Temp script参照を修正した。専有probe1件だけがFAIL対象となり、除去後の1004 YAML検証はPASS/error0/pending0。独立再確認済み。正規レビュー記録は `../レビュー結果/[完了] 26-10-15 05：29：44 code-review.md`（4/4修正、未解決なし）。

新規比較資料の改行をLFへ固定し、正本script3件と同じ入力から別Tempへ再生成した10出力がbyte単位で完全一致した。件数は主値228・戦闘2688・商品76・武器+防具28、最大BAG14で一致。既存YAMLの文字コードは変更していない。

## 残件の最終採否

`BALANCE_DECISION.md` / `BALANCE_DECISION.json` に7beca38時点の採否を記録した。本体/小セットは追加修正不要。旧完成品との差は通常0.60%・強枠0.74%で防御込み耐久はラピス優位。150.91%はLv30標準3318を分母にしたTTK最適最大構成で、通常+30は102.15%。Rだけを最大化すると166.61%になるため、最適構成込み130%目安は未達、上級刻印完成構成まで含む公開判定は保留とする。通常本体を下げる修正でレベル更新を壊さない。SQL/実戦/配置の公開前確認を同資料へ限定して記した。
