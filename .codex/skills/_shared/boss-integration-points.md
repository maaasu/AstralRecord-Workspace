# ボス実装の統合箇所

新しいボスを動かすために触る可能性がある箇所の一覧です。ボス設計書の「7. 統合箇所」では全IDについて対象・対象外を判定し、実装担当はその判定どおりに作業します。行番号は変わるため、クラス名・メソッド名と手本のボスで位置を探します。

パスの省略形: `boss/` = `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/`、`plugin/` = `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/`、`res/` = `10_plugin/AstralRecord/src/main/resources/`。

| ID | 区分 | 対象になる条件 | ファイル | 追加・変更する内容 | 手本 |
|:--|:--|:--|:--|:--|:--|
| I01 | ボスマスタ | 常に | `40_filebase/40.features.mob/boss/v1.<boss_id>.yml` | 設計書の YAML をそのまま配置する。スキーマは同ディレクトリの `docs.boss.YAMLスキーマ定義.md` | `v1.horologium_clockwork_sovereign.yml` |
| I02 | フィールドワールド | 専用の挑戦フィールドを新設する | `40_filebase/60.features.world/v1.<field_id>.yml` | `worldType: BOSS_FIELD` のワールドマスタ | `v1.umbral_orrery.yml` |
| I03 | 報酬・交換 | 専用の報酬アイテム・交換所がある | `40_filebase` の item / `45.features.shop` | アイテム・交換マスタ | コミット `3d703e9e8` の horologium 関連 YAML |
| I04 | 戦闘制御クラス | 常に | `boss/<Name>EncounterController.java`（新規） | `BOSS_ID` 定数、インスタンスごとの状態 Map、`tick` / `clear` / `clearAll` / `retain`、必要なら `incomingDamageMultiplier`。`tick` 内の例外は `LogId` で記録し、状態を閉じて戦闘を中断する | `HorologiumEncounterController` |
| I05 | 闘技場の座標 | 座標・範囲の判定がある | `boss/<Name>Arena.java`（新規） | 基準点・半径などの定数と `inside(...)` などの判定 | `NyxeronArena` |
| I06 | フィールド生成 | 地形をコードで生成する（建築済みワールドを複製する場合は対象外） | `boss/<Name>FieldGenerator.java`（新規）と `BossFieldInstanceService` | 生成器と、フィールドIDによる分岐（必要チャンク・生成・スポーン位置） | `HorologiumFieldGenerator`、`BossFieldInstanceService#isHorologiumField` |
| I07 | 戦闘制御の組み込み | 常に | `boss/BossMechanicService.java` | (1) フィールドとコンストラクタでの生成 (2) `tick()` のボス判定 boolean、先頭の除外条件 `if (profile == null && ...)` への追加、active 集合、挑戦外で `LEASHED` / `IDLE` のときの `clear`、`canAct` の算出と `tick` 呼び出し (3) `tick()` 末尾の `retain` (4) `handleMobDestroyed` の `clear` (5) `stop` の `clearAll` (6) 参加者解決メソッドと直接参加者 Map (7) `canApplyCombatDamage` の参加者判定 | `nyxeron` を含む各行 |
| I08 | 被ダメ倍率・HP下限 | 無敵・弱点・HP下限がある | `BossMechanicService#incomingDamageMultiplier` / `#minimumHealthAfterDamage` | 制御クラスへの委譲 | nyxeron の行 |
| I09 | 被弾表示 | 被弾時に表示を切り替える | `BossMechanicService#showHurt` | 制御クラスへの委譲 | nyxeron の分岐 |
| I10 | 一時効果 | 一時ステータス効果を付与する | `BossMechanicService#setTemporarySkillEffectService` | 制御クラスへの受け渡し | knocker の行 |
| I11 | プレイヤーメッセージ | 常に | `plugin/feature/player/PlayerMsgId.java`、`res/player.properties` | 設計書のメッセージ表を空き番号で登録する。規則は `astralrecord-code/references/plugin-code.md` のプレイヤーメッセージのルール | 既存ボスの `P_80xx` |
| I12 | ログ | 常に | `plugin/infrastructure/logging/LogId.java`、`res/logger.properties` | 設計書のログ表を空き番号で登録する。規則は同参照のログのルール | `E_6542` 付近 |
| I13 | パーティクル定義 | 新しい色・形の粒子を使う | `plugin/shared/effect/SharedParticleDefinitions.java` | 設計書のパーティクル表の定義 | `NYXERON_*` |
| I14 | 冒険記録の頭アイコン | `iconModel` に専用の頭モデルを使う | `plugin/feature/adventurerecord/gui/AdventureRecordMobIcon.java` | `BOSS_HEAD_ROLES` への登録 | nyxeron の行 |
| I15 | コマンド受付 | 拠点からコマンドで挑戦させる（通常は `challenge.entryLocation` のスニーク受付だけで足り、対象外） | `boss/command/BossCommand.java`、`BossTabCompleter.java`、`BossChallengeService.java` | サブコマンドと受付処理 | horologium の分岐 |
| I16 | 両版パック表示 | 専用モデルを Java 版・統合版のパックで表示する | `<Name>PackDisplay`、`ResourcePackService` / `Repository`、`BackendProtocol`、`NetworkBridgeService`、Geyser 拡張、Proxy、API の `ResourcePackManifest`、`50_resourcepack` | **別タスク（上位モデル）**。ボス設計書の実装範囲に含めない | コミット `417a4e04c` |
| I17 | 外観モデル制作 | 新しいモデルを作る | Blockbench / `50_resourcepack` | **別タスク**: `$astralrecord-mob-model-author` | - |
| I18 | フィールド建築 | 新しい建築が必要 | AstralArchitect チケット | **別タスク**: `$astralarchitect-builder` | - |

## 別タスクとの境界

I16〜I18 は判断量が多く、下位モデルに任せると完成度が落ちるため、ボス設計書の実装範囲から外します。設計書の実装範囲は、別タスクが終わっていなくても戦闘が成立するように書きます（`entityType` と `variant` による仮の外観、`DUST` による範囲表示など）。
