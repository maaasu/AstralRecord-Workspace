# E04/B07統合結果と管理表保全

E04固定40ac6d46cf9bf724955db3d9f70998fe97c8759e（7files）→11abde88c、B07固定01eeb89237e92e67442517e5fade48f1ab28e2d7（12files）→5237f8627をcherry-pick。未commit差分を取り込んでいない。B07は共通e2058adeではなくdevelop3444b74b2基点だが、新規専用12file以外を取り込まず統合先APIで検証した。元manifest/所有範囲/静的・限定コンパイル/2独立レビューの記載が十分で受入可と判断。

IceMoth/FrostBoarを各1回生成・保持・登録、同じ参照へdestroy handleMobDestroyedとonDisable冒頭nullguard stopを接続。既存全callback順序を保持。停止はBuff/Player処理・Mob破棄・Scheduler一括cancelより先。雪原のworld/座標/密度根拠なし、spawner未変更。

## 素材と名称

E04: 10a00306森グモの糸、10a00307古びた腕輪、各35%×1/COMMON/sale0。既存樹脂5%と英雄の10a00058 50%・20a00053 0.1%、lootTable等を保持。既存Lv15/Lv10の2profileで新素材以外不変。

B07: 10a00260コオリガの羽片35%×1/sale280、10a00261コオリガの粉8%×1/sale500、10a00262シモイノシシの牙35%×1/sale440。難読な氷蛾/翅/鱗/霜猪を平易化。実YAML・SPEC・manifest・専用validator・NEW_MATERIALS.csv・PLANを同期。元STATIC_RESULTSは取込時の履歴として保持。名前の中央未反映指摘AR-CODE-001は修正済み正規記録へ保存。

14種56profileのCOMBAT_COMPARISON.csv/mdを更新。IceMoth Magic/ICE65%/9秒、FrostBoar Melee/ICE105%/10秒、通常近接1.8秒を比較へ追加。属性倍率100%と種別防御を同じ抽象PlayerDで扱う仮定。低耐久蛾は理論TTK約3.4秒で通常基準5〜7秒を下回るが、同帯の耐久型/機動型との役割差として全種・装備実値確認時の評価対象。今回は一律のstat調整をしない。

## 検証

- 全899YAML/1161namespace IDs/14中央技/56新profile/20新素材PASS。E01〜E04既存8種19実効profileで新素材除去後の全データが共通基点と一致。既存spawn/world保持。
- Resource check359log/969message、traceability225source/1018methods PASS。
- 最終統合ソースでmvn -o testを1件だけ直列実行: Java本番1186sourceをコンパイル、Tests run1022/failures0/errors0/skipped0、BUILD SUCCESS、01:18min。
- 完全ログC:/Users/kaede/AppData/Local/Temp/field-mobs-lv50-b07-e04-plugin-test.log。警告は未変更GuideCompletionRewardRepositoryのserialVersionUID1件のみ（過去と同一既存）。基点の違いによる型/署名修正は不要。
- 第一独立レビューで中央名同期指摘を修正。別担当による第二独立レビューで中央接続と名・率・比較を確認。Maven終了後ビルド枠解放。

## 管理表

E04担当が禁止範囲の管理表を再生成したとの報告を受け、現在表の手動メモ、初期保持メモと各統合commit（e2058ade/2c419c1/6c796066/6248754/f4ea3ba/77b482d/2158ee47/c78fa069）、既存orb系/eriva-armor/equipment等の作業行が残ることを確認。確認できた既存メモの欠落なし。E04実行前の完全バイトsnapshotは保持しておらず、未観測行の完全同一までは証明しない。

現状47320bytesの表全体をC:/Users/kaede/AppData/Local/Temp/field-mobs-management-preserved-20261015-E04.mdへそのまま保全。保全時SHA256=1C81DF560ABBD6783B1B9FAA0F83FCA704D9A83CA88CEB45A779FC4B663D2CCE。以後は中央担当の最小追記だけで保持。重複する手動メモ見出しや既存分類を独断で整理・削除せず、全体再生成を反復しない。

実server/client、実抽選/Registry本番ロード、実配置は未確認。develop/push/version/deploy/server/DB/port操作なし。専用統合branchを保持して次batchを待つ。
