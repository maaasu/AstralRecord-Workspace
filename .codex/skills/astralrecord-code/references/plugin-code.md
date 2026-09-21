# プラグインコード

`E:\AstralRecord-Workspace\10_plugin\AstralRecord` 配下を実装するときにこの参照を使う。

## 必読資料

1. `E:\AstralRecord-Workspace\AGENTS.md`。
2. `E:\AstralRecord-Workspace\README.md` の AstralRecord プラグイン節。

## 移行済み `/code` チェックリスト

README のルールを適用した後、次を確認する。

1. README が共有/中核配置を定義していない限り、機能は `feature/<feature>/` 配下に収める。
2. ゲーム処理を `infrastructure/` に置かない。
3. `core/` にはコマンド/イベント登録入口と、README が許可する初期化だけを置く。
4. Bukkit/Paper のスレッド制約を守り、非同期作業からメインスレッド限定 API を呼ばない。
5. 適切な箇所ではプレイヤー処理に `AstPlayer` を使い、領域処理に `org.bukkit.entity.Player` を不要に渡さない。
6. DB アクセスはリポジトリレイヤーに閉じ込める。
7. 列挙型/定数ですでに表現されている値を文字列として直接記述しない。
8. ログとプレイヤーメッセージをインラインに書かない。触れる場合は下記の専門ルールを使う。
9. 公開で外部から呼ばれるメソッドには、引数、戻り値、例外、事前条件を説明する日本語 JavaDoc/KDoc を付ける。
10. 旧方式の色コードの処理にはプラグイン共通定義 `io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil` を使う。プラグインコードで `org.bukkit.ChatColor` を使わない。
11. GUI の共通挙動は各 GUI に重複実装せず共有側へ寄せる。ホットバーの閉じるアイコン / インベントリ切替を使う GUI は `io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder` と `HotbarShortcutClickSupport` を使い、GUI ごとの個別開く/クリック/閉じる分岐を増やさない。
12. 通常の AstralRecord アイテムツールチップでは、共通説明文のカテゴリ表示の右へ ` | <item ID>` を濃い灰色で表示する。それ以外の GUI名・説明文・メッセージへアイテム ID・マスタ ID・状態 ID などの内部識別子を出さない。マスタの表示名を使い、解決できない場合は ID を代替手段にせず「未登録の素材」などの汎用表示と操作不可の案内を使う。管理者向け画面では必要に応じて内部 ID を表示できる。

## 固定プレイヤーヘッドとGeyserの同期

- RPGプラグインでGUIなどの固定 `PLAYER_HEAD` スキンをプログラム定数として追加・変更する場合、`10_plugin/AstralRecordGeyserExtension/src/main/java/io/github/maaasu/astralrecordgeyser/BuiltinHeadTextures.java` の定数と登録一覧も同じ作業で同期する。削除時は他用途の参照がないことを確認してから登録を除去する。
- 登録する値は `textures` プロパティのBase64値で、Geyserの `PROFILE` 登録を使う。RPGで生成する仮のProfile UUIDを実プレイヤーUUIDとして登録しない。
- マスター由来の `iconTexture` はAPIカタログから取得するため、拡張の固定定数へ複製しない。実プレイヤーのスキンやNPC全身スキンも固定GUI定数と区別する。
- Geyserのヘッド定義は起動時登録であり、プレイ中のアイコン描画から未知のテクスチャを動的登録する設計にしない。APIと拡張の契約・起動時の取得失敗・再起動による反映条件を確認する。

## 言語の選択

- まず既存ファイルの言語に合わせる。
- 新規ファイルでは、対象ディレクトリの既存様式に従う。
- Java/Kotlin の判断には README のルールを使う。

## ログのルール

ログメッセージ、`LogId`、`logger.properties` を追加または変更するときは次のルールを使う。

1. コードにログテキストを直接書かない。
2. 既存のロガーラッパーと `LogId` を使う。
3. `logger.properties` と対応する `LogId` は同時に追加・更新する。
4. ログは既存のロガー API 経由で呼ぶ。
5. 例外をログするときは `Throwable` を保持する。
6. `printStackTrace()` だけの処理や、既存 ID と意味が重複する新規 ID を避ける。
7. 新しい ID を選ぶ前に、同じ意味を持つ既存の共通定義がないか `LogId.java`、`logger.properties`、近隣呼び出し箇所を検索する。
8. 再利用または新規選択したすべての `LogId` について、プロパティテキストと実際の操作を比較し、書式設定処理プレースホルダーが `Throwable` 以外の引数と完全に一致することを確認する。数値的に有効でも意味が異なる ID は再利用できない。
9. プラグインソース/リソースを編集した後は、コミット前に `python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <task-worktree>` を実行する。直接のロガー呼び出し、変更した `LogId` 引数に隠れた人間向け固定文、ログプレースホルダー数の不一致、重複リソースキー、ID/プロパティのずれが報告される状態で完了しない。

## プレイヤーメッセージのルール

プレイヤー向けメッセージ、`MsgId`、`player.properties` を追加または変更するときは次のルールを使う。

1. コードにメッセージテキストを直接書かない。
2. プレイヤー通知は `io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService` 経由にする。
3. `player.properties` と `MsgId` は同時に更新する。
4. `AstPlayer.sendMessage(...)` は旧方式の互換性のためだけに残す。新規または変更コードで使わない。
5. プラグイン管理のプレイヤーメッセージ処理で `Player#sendMessage(...)` を直接呼ばない。共通タグ/接頭辞とチャット振り分けルールを一貫させるため `PlayerMessageService` を使う。
6. API が管理対象のチャット書式設定用だと明示されていない限り、文字列リテラルを `sendInfo`、`sendSuccess`、`sendError`、`sendMessage`、または新しいプレイヤーメッセージ補助処理メソッドに直接渡さない。
7. 色コード、プレースホルダー、既存の文言様式を確認する。
8. プレースホルダーに色分けされたアイテム名前または別の表示文字列が入る場合、そのプレースホルダーの直後に `&r` を置き、後続のテンプレートテキストに意図した色/装飾を再適用する。置換値の色をプレースホルダー外のテキストに漏らさない。
9. 金色消費後の成功通知では、確定した金額を正確な `（消費ゴールド: {0}）` 形式で含め、実際に消費した金額を引数で渡す。メッセージテキストに金額を直接記述しない。
10. 既存メッセージの意味を変更する場合は、すべての呼び出し箇所を確認する。
11. プレイヤー向けメッセージに `name`、`title`、`description`、説明文テキストなど filebase/マスターデータの表示文字列が含まれる場合は、`PlayerMsgResource` / `PlayerMessageService` の書式設定を通すか、`ColorCodeUtil` で明示的に正規化する。マスタデータの未加工の `&` 色コードをプレイヤーに表示しない。
12. 新しいプレイヤーメッセージ ID を選ぶ前に、`PlayerMsgId.java`、機能固有の `*MsgId`、`player.properties` を検索し、すべての正本列挙型とプロパティを同じパッチで更新する。
13. 編集後はログ節のプラグインリソース検証スクリプトを実行する。このスクリプトは直接の `sendMessage` 呼び出し、コマンドメッセージ補助処理に渡す文字列リテラル、重複プロパティキー、プレイヤー ID/プロパティのずれも拒否する。

## データベース・API・ファイルベース の取り決め

プラグイン側の DB アクセス、DB 契約に依存する機能、スキーマ関連作業、ファイルベースのマスタデータに依存する機能を追加または変更するときは次のルールを使う。

1. リポジトリの入力/出力モデルを確認する。
2. `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` の API 契約を確認する。
3. `E:\AstralRecord-Workspace\00_docs\40_Database設計書\` 配下の SQL Server定義を確認する。
4. `E:\AstralRecord-Workspace\40_filebase\` 配下のファイルベースのマスタデータと YAML スキーマを確認する。
5. DB スキーマに依存するコードを書く前に、`00_docs\40_Database設計書` の定義と実装が一致することを確認する。
6. ファイルベース に依存するコードを書く前に、`40_filebase` の YAML とスキーマ定義が一致することを確認する。
7. テーブルまたは列の変更を含む場合は、`00_docs\40_Database設計書` に対応する更新が必要か確認する。
8. ファイルマスタ構造の変更を含む場合は、`40_filebase` に対応する更新が必要か確認する。
9. API とプラグインの契約変更を片側だけで完了させない。
10. データベース/Filebase の定義を確認せず、DB 名前や YAML パスを直接記述しない。
11. インベントリや装備耐久性などプレイヤー所有の実行時状態では、ゲームプレイ中の正本をプラグイン側の読み込み済み状態とする。戦闘または頻繁なパスで API 書き込みをブロックしない。耐久性など同様に重要度の低い状態は未コミット変更ありとして記録し、プレイヤーインベントリと同じ保存境界（自動保存、ログアウト、プラグイン無効化、明示的保存）で書き出しする。即時の API 整合性よりサーバー性能を優先する。

## プラグイン設計書

ユーザーが `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\` 配下のプラグイン設計書を作成または変更するよう依頼した場合だけ、次のルールを使う。

1. `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\README.md` を読む。
2. 機能が特定されている場合は、その `NN_0-概要.md` 入口と、実装所有権が関係する場合の `FEATURE_CATALOG.md` を読む。
3. 推測的な説明を避けるため、対応する実装コードを読む。
4. ルートカテゴリ `0/1/2/3/4/5/6/8/9` は内容がある場合だけ使う。カテゴリ `0` だけは必須とする。
5. ファイル名は `NN_<category>-<meaningful-name>.md` とする。`.00` / `.01` の詳細番号を追加しない。
6. 機能ルートにはカテゴリ文書を1つだけ置く。カテゴリディレクトリは複数の文書がある場合だけ作る。
7. 長いファイルは一貫した責務で分割し、順序番号ではなく意味のある名前を使う。
8. 受け入れ済みだが未実装の仕様はカテゴリ `8`、未決の設計判断はカテゴリ `9` に置く。推測したり状態を混在させたりしない。
9. 責務または所有権が変わる場合は機能概要と `FEATURE_CATALOG.md` を整合させる。
10. ルート設計書ルールに従い、一意に解決できる Wiki リンクまたは相対 Markdown リンクを使う。
11. メソッド設計書は処理契約として扱い、すべての物理メソッドの必須一覧とはみなさない。
12. properties ファイルが正本の場合、ロガー/プレイヤーメッセージテキスト全体を重複記載しない。

## プラグインテストのトレーサビリティゲート

作業差分が次のパスのいずれかを追加、変更、名前変更、削除する場合は、リポジトリルートから `python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py` を実行する。テストソースが変わらず設計書のみまたはテスト方針のみの差分でも、このゲートは必須とする。

- `10_plugin/AstralRecord/src/test/**/*`
- `10_plugin/AstralRecord/pom.xml`
- `PLUGIN_GUIDE.md`
- `00_docs/10_Plugin設計書/**/*.md`
- `.codex/skills/astralrecord-plugin-test/**/*`
- `.codex/skills/astralrecord-code/SKILL.md`
- `.codex/skills/astralrecord-code/references/plugin-code.md`
- `.codex/skills/astralrecord-code-version-commit-develop/SKILL.md`
- `.codex/skills/astralrecord-docs-fix/SKILL.md`

最終の Maven テスト実行前とレビューへの引き渡し前にゲートを実行する。追跡されていないテストメソッド、無効化されたまたは条件付きでスキップされるテスト、標準外の Maven テストソース、コンパイラー/Surefire から除外されたテスト、Kotlin JUnit アノテーション別名、一時的な `AdHoc*Test` / `*OneShotTest` ソース、MockBukkit等の疑似サーバーを利用するsource・support・ビルド依存を最終差分に残さない。疑似サーバーテストは一時診断として実行後に削除する。プラグイン Shade 設定がメインワークスペースの配布先へ書き込むため、このコマンドを `mvn verify` で代替しない。

## 個別実装指示の例

`表示アイテムを apple から iron_ingot に変更` のような直接依頼では次を行う。

1. 旧値と近隣機能の用語を両方検索する。
2. 文字列置換より列挙型/素材/定数/リソースの定義を優先する。
3. 変更された契約が要求する場合、またはユーザーが依頼した場合だけテスト、ファイルベース 参照、メッセージ、設計書を更新する。
4. 対象を絞ったコンパイルまたはテストを実行し、意図しない広範囲置換がないか差分を確認する。

## パーティクルルール

1. パーティクル描画は io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService 経由にする。
2. 機能コードからワールド#spawnParticle(...) またはプレイヤー#spawnParticle(...) を直接呼ばない。
3. 共有パーティクル種類、別名、既定視覚的なパラメーターは io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions に定義する。
4. 機能コードに Particle.valueOf(...) の解析を重複させず、共有解決処理を使う。
5. 繰り返すパーティクル作業には実行間隔、点件数、閲覧者走査の上限を設ける。明示的な性能計測の根拠がない限り、毎ティックまたはそれに近い常時有効効果を避ける。
6. 繰り返す効果が同じ中心に複数点を描画する場合、点ごとに spawnForNearbyViewers を呼ばず、ParticleDisplayService 経由で近くの閲覧者解決を一括処理化する。
7. 閲覧者が存在し得ないワールドまたは中心の繰り返すパーティクル作業はスキップし、パケット件数は読み込み済みワールドではなく表示対象プレイヤー数に比例させる。

## プレイヤーテレポートのルール

1. プレイヤーテレポートの挙動では、テレポート直前のプレイヤーの yaw / pitch を保持する。
2. 新しいプレイヤーテレポート機能では `io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService`、または `WorldService#teleportPlayerAsync(...)` のようにこれへ委譲する既存サービスメソッドを使う。
3. 機能が対象で定義した yaw / pitch を明示的に必要とし、その例外を文書化していない限り、プラグイン管理のプレイヤー移動で `Player#teleport(...)` や `Player#teleportAsync(...)` を直接呼ばない。
4. エンティティ、表示、パケット、表示だけの移動はこのルールの対象外であり、既存の移動 API を使い続けてよい。
