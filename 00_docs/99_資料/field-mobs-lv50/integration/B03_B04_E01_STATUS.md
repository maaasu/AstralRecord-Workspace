# B03/B04/E01中央統合・検証結果

B03原a75e96998d5cd215c3231bbd581900698a68f306→ee550ee04、B04原5810819faa9de909de498e7e4ef80357a3ff31b9→5a89eb692、E01原462863d9727028d1ce141cea44df22e45af925ec→edb8fdfc9を統合専用branchへcherry-pick。自種14/16fileと既存2種7fileの所有範囲を確認。既存B01/B02・スキーマ接続を保持。

## 中央登録・cleanup

PlainRam/BladeMantis/StonebackBison/StormRaptorを各1インスタンス保持・登録し、同じ参照へdestroy clearCasterStateとonDisable stopを接続。既存destroy listenerのcallbackと順番は保持、stopはMob一括破棄/Scheduler一括cancelより先。B04は既存finishを呼ぶclearCasterStateだけを追加し、元のstopとPluginDisableEventを維持。Raptorの重力・空中状態回収も既存finishに委譲。

第一独立レビューと別担当の第二独立レビューで中央型/署名・登録順・同一instance・cleanupの冪等性、既存callback保持、E01比較範囲、確率・比較式に重大/中指摘なし。

## 大地の芯の極低確率設計

10a00232を1%→0.25%・個数1へ変更（全4levelはroot items継承）。角10%の1/40、通常35%の1/140。無補正で1撃破1抽選なら平均400撃破、少なくとも1個を95%で得るには約1197抽選。既存通常希少3〜7%と明確に分け、既存の極低確率装備0.1%との間に設定。幸運/増加率が適用されるため実効率は変わる。

反復可能な通常フィールド強敵の素材なので、1%の平均100撃破では今回の「かなり低確率」枠に近隣通常希少との差が小さいと判断。B04の仮定TTK14.5秒なら平均400撃破の純戦闘約97分、共通直線仮定ではTTK18〜21.4秒なので約120〜143分。移動/遭遇/待ち時間は未計測で、実際の討伐頻度を捏造しない。売却期待値は263→242、ラプター91を維持。装備必須の大量消費素材として採用する前に装備担当がこの希少性を考慮する。

B04 SPEC、enemy実YAML、NEW_MATERIALS.csv rate_finalを0.25へ同期。元batch検証ログとchance_draft1%は取込時の履歴として保持、確定値はfinal列が正本。

## 完了した検証

- validate_integrated_batches.py: YAML878 / namespace IDs1140 / 中央8技 / 新32profile / 新素材12。E01の追加2素材・全8実効profileで新素材を除去した残りがe2058ade基点と完全一致。root全体も比較し既存level削除等を検出。既存spawner/world変更なし。
- B03専用validator: 全878導入前の876 YAMLでprofile/参照/予約の確認に成功。B04専用validator: 0.25%調整後の876 YAMLで専用6 YAML・status/tag・参照・dropに成功。各出力の未登録表示はバッチ時点の履歴表現で、現在は中央source登録確認済み。
- resource check: 359 log IDs / 969 player message IDsでPASS。
- test traceability: 225sourceの1018methodsでPASS。
- 一時JDK syntax parse: AstralRecordとB03/B04の4Executor、計5sourcesでPASS（依存/type解析なし）。
- 最終統合sourceでmvn -o testを1件直列実行: Java本番1180filesをコンパイル、Tests run1022 / failures0 / errors0 / skipped0、BUILD SUCCESS、01:17min。完全ログC:/Users/kaede/AppData/Local/Temp/field-mobs-lv50-b03-b04-e01-plugin-test.log。

唯一の警告は未変更GuideCompletionRewardRepository.RewardUnavailableExceptionのserialVersionUID未定義。前回とスキーマ担当の同一既存警告で、新規作業起因警告なし。新規共通テストや個別Mob恒久JUnitは追加しない。

## 数値比較と状態

COMBAT_COMPARISON.csv/mdとgenerate_combat_comparison.pyへ8種32profileを実マスタから記録。HP/R/D/通常間隔/CD/技倍率/EXP/期待売却を照合し、TTKはPlayer通常DPS×1.6の仮定、耐久は通常+技加算の保守的下限。Lv30超はL直線/CのHP増分延長/Q二乗延長を明示し、同一仮定で評価できるようにした。CのR/DはLと同じ。

例: カマキリLv48のTTKはL約5.14秒/Q約3.78秒、バイソンはL約21.39秒/Q約14.30秒。種のrole差と暫定曲線差を分離する。全23種が揃った時点で上位装備/職業実値、skillで失う通常攻撃、回避/密度、通常TTK5〜7秒/耐久8〜10秒、強敵別目標を照合して曲線と必要な個体値を確定する。今回はステータスを一律変更しない。

NEW_MATERIALS.csvはB01〜B04の12素材を実装・compile/test成功・runtime待ちとして更新。EXISTING_MATERIAL_ASSIGNMENTS.csvはE01の2素材を実装・8実効profile保持・runtime待ちとして更新。E02以降は予約のまま。

実server/client、Registry/MobRepositoryの本番master実ロード、spawn配置/密度/座標/実戦TTKは未実施。world/座標を捏造せずspawnerは保留。Maven終了後ビルド枠を解放、次回重い検証は親の再割当で直列実行。develop/push/version/deployなし、統合worktreeを保持。
