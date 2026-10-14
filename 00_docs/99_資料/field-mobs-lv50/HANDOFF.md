# 実装開始の引き継ぎ

計画コミットを全バッチの共通基点にする。親はB01/B02から各専用branch/worktreeを作成。既存メインdevelopや他batchのworking treeを共有しない。通常のworktree skillのdevelop基点規則に対する今回の明示指定は「計画コミット基点」を優先する。

BATCH_IDS.csvのmob/skill IDを固定予約。Executorの置き場はfeature/mob/skill/fieldで各種1ファイル。クラス名は参考予約、複数技が必要なら自種接頭辞の専用ファイルを追加してmanifestに記録。loot/buffは必要な場合だけ自種接頭辞で作成。既存共有基盤の変更はしない。

各batch所有: 自分の1〜2種enemy、新素材の割当10枠、自種専用loot/buff/Executor、00_docs/99_資料/field-mobs-lv50/batches/Bnn/の仕様・静的検証・中央変更依頼manifest。PLAN/CURRENT_FIELDS/VALIDATION/CSVの中央資料は編集しない。

中央変更依頼manifestには登録ID、完全修飾Executorクラス、constructor依存、登録コード案、必要なproperties/LogId/MsgIdや基盤変更、スポーンの生息想定と確認済み参照根拠、未検証事項を列挙する。AstralRecord.java/Registry/pom/config/shared resources/既存loot/全spawner/管理表は統合担当が単独編集する。

最初のB01/B02は重いbuildを行わず、YAML parse・schema再読・全ID重複・変更参照・既存drop保持・レベル差/上限・Executorコード静的レビュー・resource checkを実行。中央登録前の未登録skillはmanifestで明示する。全体build/testと実server確認を実施済みと書かない。

各deviceスレッドは1〜2種を作り込み、数値/命名を自律判断して根拠を保存。完成した自種ファイルだけ限定commitし、SHA/所有パス/中央変更依頼/検証/未検証を親へ返してworktreeを保持。push/develop反映/version/deploy/server/DB/port操作なし。

追加指示反映: 計23種/12batch。平原8/森林6/雪原3/メサ3/火山3。追加B11は2種、B12は1種。素材追加予約10a00400〜00419。既存予約/既存batchを変更しない。装備側はNEW_MATERIALS.csvの33素材を参照する。
