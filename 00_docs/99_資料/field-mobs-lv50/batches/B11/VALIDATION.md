# B11 検証と引き継ぎ

`validate_b11.py --repo-root <B11-root>` で全filebase YAMLの重複キー付きparse、namespace別ID重複、所有パス、8レベルの親子merge、stats/tag、モデル/アイコン対応、通常攻撃・固有skill timing、3素材参照、確率/数量/表示/売却・取引、設計メタデータ、報酬継承を確認する。結果は `STATIC_RESULTS.json`。これは個別Mobの恒久unit testではなく、マスターと引き継ぎ資料の検証である。

`JAVAC_RESULT.json` は新規Executor2件だけをJDK21でコンパイルした完全出力とsource SHA256。既存main target/classesとローカルMaven cacheを使用し、`-proc:none -encoding UTF-8 -Xlint:all -implicit:none`、sourcepath空、class出力は一時ディレクトリ。全体build/testの代替ではない。結果はexit0、出力なし、警告0。

Plugin resource checkは `check_plugin_resources.py --repo-root <B11-root>`。359 log IDs / 969 player message IDsで成功。新規LogId/MsgId/properties、共有Particle定義の追加なし。個別Mobの恒久unit testは追加しない。テスト・POM・許可された設計入力の変更がないためtest traceabilityは対象外。

Paper 1.21.11 APIの `javap org.bukkit.Material` で `FEATHER` / `PHANTOM_MEMBRANE` / `GOAT_HORN` / `PARROT_SPAWN_EGG` / `GOAT_SPAWN_EGG` の実在を確認。モデルとスポーンエッグは全profileで親から継承する。

編集サイトは `MasterDataPaths.Enumerate` がfilebaseを再帰走査し、`MasterDataService.ListAsync/ReadAsync` が各ファイルをcodecで解析、`MasterDataCatalog.ReadAsync` が同じ走査からカテゴリを構成する。今回の追加は既存の敵・素材契約内なのでソース同期は不要。この経路を静的確認しただけで、サイト起動・画面操作・保存は行っていない。

中央登録前なので両implementationIdは `registration_pending` として出力する。これは未解決参照の無視ではなく `MobSkillExecutor` の直接登録契約による明示的な統合待ち。manifestのimport/field/生成・registry登録、destroy/disable callbackを統合後に実行してから動作確認する。

独立Solレビューを2回実施。双方とも最新差分に確定指摘なし。実装中の静的確認でシカの掃引hitboxを予定位置へ補正し、0.15m超の外部移動で中断するようにした点も両者が再確認した。指摘のないレビューなので正規レビュー記録は作成しない。

実server/client/Geyser、全体build/test、編集サイトUI、world/座標配置は未実行。Lv31以降の正式なclass/装備基準と現行Rの不足はSPECのとおり、実配置受入を保留する。管理表は変更しない。
