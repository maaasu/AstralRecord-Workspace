# Plugin コード

`E:\AstralRecord-Workspace\10_plugin\AstralRecord` 配下を実装するときにこの reference を使う。

## 必読資料

1. `E:\AstralRecord-Workspace\AGENTS.md`。
2. `E:\AstralRecord-Workspace\README.md` の AstralRecord Plugin 節。

## 移行済み `/code` チェックリスト

README のルールを適用した後、次を確認する。

1. README が shared/core placement を定義していない限り、feature は `feature/<feature>/` 配下に収める。
2. game logic を `infrastructure/` に置かない。
3. `core/` には command/event registration entry point と、README が許可する bootstrap だけを置く。
4. Bukkit/Paper の thread 制約を守り、async work から main-thread-only API を呼ばない。
5. 適切な箇所では player handling に `AstPlayer` を使い、domain logic に `org.bukkit.entity.Player` を不要に渡さない。
6. DB access は repository layer に閉じ込める。
7. enum/constant ですでに表現されている値を string として hard-code しない。
8. log と player message を inline に書かない。触れる場合は下記の専門ルールを使う。
9. public で外部から呼ばれる method には、引数、戻り値、例外、precondition を説明する日本語 JavaDoc/KDoc を付ける。
10. legacy color code の処理には Plugin 共通定義 `io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil` を使う。Plugin code で `org.bukkit.ChatColor` を使わない。
11. GUI の共通挙動は各 GUI に重複実装せず shared 側へ寄せる。ホットバーの閉じるアイコン / インベントリ切替を使う GUI は `io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder` と `HotbarShortcutClickSupport` を使い、GUI ごとの個別 open/click/close 分岐を増やさない。
12. 通常の AstralRecord item tooltip では、共通 Lore のカテゴリ表示の右へ ` | <item ID>` を濃い灰色で表示する。それ以外の GUI名・lore・メッセージへ item ID・master ID・status ID などの内部識別子を出さない。マスタの表示名を使い、解決できない場合は ID を fallback にせず「未登録の素材」などの汎用表示と操作不可の案内を使う。管理者向け画面では必要に応じて内部 ID を表示できる。

## 固定プレイヤーヘッドとGeyserの同期

- RPGプラグインでGUIなどの固定 `PLAYER_HEAD` スキンをプログラム定数として追加・変更する場合、`10_plugin/AstralRecordGeyserExtension/src/main/java/io/github/maaasu/astralrecordgeyser/BuiltinHeadTextures.java` の定数と登録一覧も同じtaskで同期する。削除時は他用途の参照がないことを確認してから登録を除去する。
- 登録する値は `textures` プロパティのBase64値で、Geyserの `PROFILE` 登録を使う。RPGで生成する仮のProfile UUIDを実プレイヤーUUIDとして登録しない。
- マスター由来の `iconTexture` はAPIカタログから取得するため、Extensionの固定定数へ複製しない。実プレイヤーのスキンやNPC全身スキンも固定GUI定数と区別する。
- Geyserのヘッド定義は起動時登録であり、プレイ中のアイコン描画から未知のテクスチャを動的登録する設計にしない。APIとExtensionの契約・起動時の取得失敗・再起動による反映条件を確認する。

## 言語の選択

- まず既存 file の言語に合わせる。
- 新規 file では、対象 directory の既存 style に従う。
- Java/Kotlin の判断には README のルールを使う。

## Log のルール

log message、`LogId`、`logger.properties` を追加または変更するときは次のルールを使う。

1. code に log text を直接書かない。
2. 既存の logger wrapper と `LogId` を使う。
3. `logger.properties` と対応する `LogId` は同時に追加・更新する。
4. log は既存の logger API 経由で呼ぶ。
5. exception を log するときは `Throwable` を保持する。
6. `printStackTrace()` だけの処理や、既存 ID と意味が重複する新規 ID を避ける。
7. 新しい ID を選ぶ前に、同じ意味を持つ既存の共通定義がないか `LogId.java`、`logger.properties`、近隣 call site を検索する。
8. 再利用または新規選択したすべての `LogId` について、property text と実際の operation を比較し、formatter placeholder が `Throwable` 以外の引数と完全に一致することを確認する。数値的に有効でも意味が異なる ID は再利用できない。
9. Plugin source/resource を編集した後は、commit 前に `python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <task-worktree>` を実行する。direct logger call、変更した `LogId` 引数に隠れた人間向け固定文、log placeholder 数の不一致、重複 resource key、ID/property のずれが報告される状態で完了しない。

## Player message のルール

player 向け message、`MsgId`、`player.properties` を追加または変更するときは次のルールを使う。

1. code に message text を直接書かない。
2. player notification は `io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService` 経由にする。
3. `player.properties` と `MsgId` は同時に更新する。
4. `AstPlayer.sendMessage(...)` は legacy compatibility のためだけに残す。新規または変更 code で使わない。
5. Plugin 管理の player messaging で `Player#sendMessage(...)` を直接呼ばない。共通 tag/prefix と chat routing rule を一貫させるため `PlayerMessageService` を使う。
6. API が managed chat formatting 用だと明示されていない限り、string literal を `sendInfo`、`sendSuccess`、`sendError`、`sendMessage`、または新しい player-message helper method に直接渡さない。
7. color code、placeholder、既存の wording style を確認する。
8. placeholder に color-coded item name または別の display string が入る場合、その placeholder の直後に `&r` を置き、後続の template text に意図した color/decor を再適用する。replacement value の color を placeholder 外の text に漏らさない。
9. gold 消費後の success notification では、確定した金額を正確な `（消費ゴールド: {0}）` 形式で含め、実際に消費した金額を引数で渡す。message text に金額を hard-code しない。
10. 既存 message の意味を変更する場合は、すべての call site を確認する。
11. player 向け message に `name`、`title`、`description`、lore text など filebase/master-data の display string が含まれる場合は、`PlayerMsgResource` / `PlayerMessageService` の formatting を通すか、`ColorCodeUtil` で明示的に normalize する。master data の raw `&` color code を player に表示しない。
12. 新しい player message ID を選ぶ前に、`PlayerMsgId.java`、feature 固有の `*MsgId`、`player.properties` を検索し、すべての正本 enum と property を同じ patch で更新する。
13. 編集後は Log 節の Plugin resource validation script を実行する。この script は direct `sendMessage` call、command message helper に渡す string literal、重複 property key、player ID/property のずれも拒否する。

## Database・API・Filebase の contract

Plugin 側の DB access、DB contract に依存する feature、schema 関連作業、file-based master data に依存する feature を追加または変更するときは次のルールを使う。

1. repository の input/output model を確認する。
2. `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` の API contract を確認する。
3. `E:\AstralRecord-Workspace\00_docs\40_Database設計書\` 配下の SQL Server 定義を確認する。
4. `E:\AstralRecord-Workspace\40_filebase\` 配下の file-based master data と YAML schema を確認する。
5. DB schema に依存する code を書く前に、`00_docs\40_Database設計書` の定義と実装が一致することを確認する。
6. filebase に依存する code を書く前に、`40_filebase` の YAML と schema 定義が一致することを確認する。
7. table または column の変更を含む場合は、`00_docs\40_Database設計書` に対応する更新が必要か確認する。
8. file master 構造の変更を含む場合は、`40_filebase` に対応する更新が必要か確認する。
9. API と Plugin の contract 変更を片側だけで完了させない。
10. Database/Filebase の定義を確認せず、DB name や YAML path を hard-code しない。
11. inventory や equipment durability など player 所有の runtime state では、gameplay 中の正本を Plugin 側の loaded state とする。combat または hot path で API write を block しない。durability など同様に criticality の低い state は dirty として記録し、player inventory と同じ save boundary（autosave、logout、Plugin disable、明示的 save）で flush する。即時の API 整合性より server performance を優先する。

## Plugin 設計書

ユーザーが `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\` 配下の Plugin 設計書を作成または変更するよう依頼した場合だけ、次のルールを使う。

1. `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\README.md` を読む。
2. feature が特定されている場合は、その `NN_0-概要.md` entry point と、実装 ownership が関係する場合の `FEATURE_CATALOG.md` を読む。
3. 推測的な説明を避けるため、対応する実装 code を読む。
4. root category `0/1/2/3/4/5/6/8/9` は内容がある場合だけ使う。category `0` だけは必須とする。
5. file name は `NN_<category>-<meaningful-name>.md` とする。`.00` / `.01` の detail number を追加しない。
6. feature root には category document を1つだけ置く。category directory は複数の document がある場合だけ作る。
7. 長い file は一貫した責務で分割し、sequence number ではなく意味のある name を使う。
8. 受け入れ済みだが未実装の仕様は category `8`、未決の設計判断は category `9` に置く。推測したり state を混在させたりしない。
9. 責務または ownership が変わる場合は feature 概要と `FEATURE_CATALOG.md` を整合させる。
10. root docs rule に従い、一意に解決できる Wiki link または相対 Markdown link を使う。
11. method docs は処理 contract として扱い、すべての物理 method の必須一覧とはみなさない。
12. properties file が正本の場合、logger/player message text 全体を重複記載しない。

## Plugin test のトレーサビリティゲート

task diff が次の path のいずれかを追加、変更、rename、削除する場合は、repository root から `python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py` を実行する。test source が変わらず design-doc-only または test-policy-only の diff でも、この gate は必須とする。

- `10_plugin/AstralRecord/src/test/**/*`
- `10_plugin/AstralRecord/pom.xml`
- `PLUGIN_GUIDE.md`
- `00_docs/10_Plugin設計書/**/*.md`
- `.codex/skills/astralrecord-plugin-test/**/*`
- `.codex/skills/astralrecord-code/SKILL.md`
- `.codex/skills/astralrecord-code/references/plugin-code.md`
- `.codex/skills/astralrecord-code-version-commit-develop/SKILL.md`
- `.codex/skills/astralrecord-docs-fix/SKILL.md`

final Maven test 実行前と review handoff 前に gate を実行する。trace されていない test method、disabled または条件付きで skip される test、標準外の Maven test source、compiler/Surefire から除外された test、Kotlin JUnit annotation alias、ad-hoc `AdHoc*Test` / `*OneShotTest` source を最終 diff に残さない。Plugin shade configuration がメイン workspace の配布先へ書き込むため、この command を `mvn verify` で代替しない。

## 個別実装指示の例

`表示アイテムを apple から iron_ingot に変更` のような直接依頼では次を行う。

1. 旧値と近隣 feature の用語を両方検索する。
2. string replacement より enum/material/constant/resource の定義を優先する。
3. 変更された contract が要求する場合、またはユーザーが依頼した場合だけ test、filebase reference、message、docs を更新する。
4. targeted compile または test を実行し、意図しない広範囲置換がないか diff を確認する。

## Particle ルール

1. Particle rendering は io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService 経由にする。
2. feature code から World#spawnParticle(...) または Player#spawnParticle(...) を直接呼ばない。
3. shared particle species、alias、既定 visual parameter は io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions に定義する。
4. feature code に Particle.valueOf(...) の parse を重複させず、shared resolver を使う。
5. recurring particle task には cadence、point count、viewer scan の上限を設ける。明示的な profiling の根拠がない限り、毎 tick またはそれに近い常時有効 effect を避ける。
6. recurring effect が同じ center に複数 point を描画する場合、point ごとに spawnForNearbyViewers を呼ばず、ParticleDisplayService 経由で nearby-viewer resolution を batch 化する。
7. viewer が存在し得ない world または center の recurring particle work は skip し、packet count は loaded world ではなく表示対象 player 数に比例させる。

## Player teleport ルール

1. Player teleport の挙動では、teleport 直前の player の yaw / pitch を保持する。
2. 新しい player teleport feature では `io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService`、または `WorldService#teleportPlayerAsync(...)` のようにこれへ委譲する既存 service method を使う。
3. feature が target-defined yaw / pitch を明示的に必要とし、その例外を文書化していない限り、Plugin 管理の player movement で `Player#teleport(...)` や `Player#teleportAsync(...)` を直接呼ばない。
4. entity、display、packet、visual-only movement はこのルールの対象外であり、既存の movement API を使い続けてよい。
