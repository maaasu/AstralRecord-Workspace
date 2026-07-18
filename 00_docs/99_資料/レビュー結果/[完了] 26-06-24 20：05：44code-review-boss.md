# ボス機能 コードレビュー結果

- 対象: `C:\AstralRecord-Worktrees\boss-feature`
- ブランチ: `codex/boss-feature`
- 観点: ボス挑戦フロー、インスタンスワールド、転送、報酬、設計書整合
- 実施日: 2026-06-24
- 備考: ソースレビューのみ。現在の PowerShell 環境では `git` が PATH 上に見つからなかったため、追加の `git diff` / テスト実行は未実施。

## 指摘一覧

### AR-CODE-001 [重大] ボス討伐報酬がフィールド破棄で失われる可能性がある

- 対象:
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/DamageService.java:342`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java:325`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobDropPresentationService.java:213`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobDropPresentationService.java:332`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossChallengeService.java:383`
- 内容:
  - ボス死亡時、`DamageService` は `mobCombatService.handleDeath(mob)` で通常 Mob と同じドロップ演出・付与を開始した直後に、`bossChallengeService.handleBossDefeated(...)` で挑戦終了へ進む。
  - `MobDropPresentationService` はドロップ演出を tick タスクで進め、完了時に `grantPreparedItem` する。演出キャンセル時は死亡地点へ drop する設計だが、`BossChallengeService.endChallenge` は参加者をハブへ戻してすぐ `destroyField` し、ワールドを unload / delete する。
  - そのため、演出完了前にボスフィールドが破棄されると、アイテムがプレイヤーに付与されず、キャンセル時の drop 先ワールドも破棄済みになり、報酬が消失する可能性がある。
- 影響:
  - ボス討伐成功時に、経験値・戦績だけ進み、アイテム報酬が消える。
  - 装備・ルーンなどインスタンス生成済みアイテムの場合、API 側に生成済みデータだけ残り、プレイヤー所持品にもワールド drop にも存在しない状態になる可能性がある。
- 修正案:
  - ボス報酬は演出完了を待たず、まず参加者へ確定付与する。
  - 演出は結果表示用に分離するか、全 `presentAndGrant` の完了を待ってからフィールド破棄へ進む。
  - 少なくともボスフィールド破棄前に、未完了のドロップ Future を同期して補償付与する。

### AR-CODE-002 [重大] ボス報酬対象が挑戦参加者に限定されていない

- 対象:
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/DamageService.java:342`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java:308`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java:337`
  - `00_docs/10_Plugin設計書/feature/26-boss/2-ユースケース/26_2.00-ユースケース.md:92`
  - `00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3.02-サービス.md:102`
- 内容:
  - 設計では、ボス報酬は「ボス挑戦インスタンスに参加しているパーティーメンバーだけ」「討伐時に同じワールドにいる参加者」に限定する。
  - 実装ではボス死亡時も通常 Mob の `MobCombatService.handleDeath` をそのまま呼び、報酬対象は threat table と killer の現在パーティー・距離で決まる。
  - `BossChallengeInstance.participantIds()` によるフィルタがない。
- 影響:
  - 何らかの手段でボスフィールドに入った非参加者がダメージを入れると報酬対象になり得る。
  - 逆に、参加者であっても threat table に載らず、killer の現在パーティー条件から外れた場合、設計上は対象なのに報酬を受け取れない。
  - 挑戦開始後にパーティー状態が変わると、受付時点の固定参加者ではなく現在パーティーが報酬判定に使われる。
- 修正案:
  - `BossRewardService` などでボス専用報酬経路を作り、`challenge.participantIds()` と討伐時のフィールドワールド一致で対象を確定する。
  - 通常 Mob の drop roll / exp / money 付与処理を再利用する場合も、recipient 決定だけはボス挑戦コンテキストで上書きする。

### AR-CODE-003 [高] スニーク受付イベント上でワールドコピーとロードを同期実行している

- 対象:
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/event/BossEntryEventHandler.java:21`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossChallengeService.java:200`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossFieldInstanceService.java:55`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossFieldInstanceService.java:110`
  - `00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3.02-サービス.md:32`
- 内容:
  - `PlayerToggleSneakEvent` から `acceptNearestChallenge` が呼ばれ、そのまま `fieldInstanceService.createField` に入り、`Files.walk` / `Files.copy` によるテンプレートワールドコピーと `Bukkit.createWorld` を実行している。
  - 設計では `COPYING` 状態を持ち、可能な限り非同期でテンプレートワールドをコピーし、コピー完了後にメインスレッドで Bukkit ワールドをロードする流れになっている。
- 影響:
  - テンプレートワールドが大きいほど、スニークした瞬間にサーバーメインスレッドが停止する。
  - 複数プレイヤーや連続受付で tick 落ち・タイムアウト・全プレイヤーへのラグとして現れる。
- 修正案:
  - `BossFieldInstanceService` を「非同期コピー」と「メインスレッドロード」に分離する。
  - `BossChallengeInstance` を `CREATING_FIELD` / `READY_TO_ENTER` 相当に遷移させ、コピー中は再受付を抑止する。
  - Bukkit API を触る `Bukkit.createWorld` / game rule 適用だけメインスレッドへ戻す。

### AR-CODE-004 [高] フィールド転送の完了・失敗を待たずにボス戦を開始している

- 対象:
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossChallengeService.java:312`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossChallengeService.java:317`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/world/service/WorldService.java:239`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/world/service/WorldService.java:256`
  - `00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3.02-サービス.md:48`
- 内容:
  - `BossChallengeService.startField` は各参加者へ `worldService.teleportPlayerAsync(...)` を投げるが、返却される `CompletableFuture<Boolean>` を保存も待機もしていない。
  - 直後にボスを spawn し、`markStarted()` で戦闘開始状態にする。
  - `WorldService.teleportPlayerAsync` はチャンクロードと delayed task を含むため、即時完了ではない。
- 影響:
  - 参加者の転送が失敗しても `TRANSFER_FAILED` にならず、ボスだけが生成される。
  - 転送が遅延した場合、watchdog が「フィールド内参加者なし」と判定して `NO_PARTICIPANTS` 終了する可能性がある。
  - 設計上の「転送成功した参加者を participants に登録」「必須転送失敗時は TRANSFER_FAILED」とズレる。
- 修正案:
  - 参加者分の `CompletableFuture<Boolean>` を集約し、全必須参加者の成功後にボス spawn / `IN_PROGRESS` へ進める。
  - 1人でも必須転送失敗なら `TRANSFER_FAILED` で終了し、生成済みフィールドを確実に破棄する。

### AR-CODE-005 [中] フィールドワールド設定の検証不足により空のボスフィールドが生成され得る

- 対象:
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossChallengeService.java:177`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossFieldInstanceService.java:55`
  - `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/BossFieldInstanceService.java:59`
  - `40_filebase/60.features.world/v1.twilight_colossus_field.yml:4`
  - `00_docs/10_Plugin設計書/feature/26-boss/5-例外・ログ・運用/26_5.00-例外・ログ・運用.md:11`
- 内容:
  - 設計では `worldType = BOSS_FIELD` かつ `instanceEnabled = true` を必須とし、テンプレートコピー失敗は `FIELD_CREATE_FAILED` として終了する。
  - 実装は `worldService.getById(config.fieldWorldId())` の存在確認だけで、`worldType` / `instanceEnabled` を検証していない。
  - `baseWorldPath` が存在しない場合も警告ログ後に `Files.createDirectories(target)` して `Bukkit.createWorld` するため、テンプレート不備が空ワールド生成として成功扱いになる。
- 影響:
  - filebase の typo や配置漏れに気づけず、想定外の地形・空フィールドでボス戦が開始される。
  - `BOSS_FIELD` ではない通常ワールド設定を `fieldWorldId` に指定しても挑戦開始できる可能性がある。
- 修正案:
  - `fieldData.worldType() == BOSS_FIELD` と `fieldData.instanceEnabled()` を受付前に検証する。
  - `baseWorldPath` 不在は `IOException` として扱い、`FIELD_PREPARE_FAILED` / `FIELD_CREATE_FAILED` で終了する。
  - 失敗途中で作成した target folder は catch/finally で削除する。

## 補足

- 起動時残存フィールド掃除は設計に存在するが、`BossChallengeService.start()` は watchdog のみ開始しており、`BossFieldStartupCleanupTask` 相当は見当たらなかった。README 側に初回実装の未実装範囲として記載があるため、今回の主指摘には含めず、既知の未実装事項として扱う。
- `MobChallengeResponse` / `MobRepository.parseChallenge` / 実マスタは `fieldWorldId` / `entryRadius` に寄せられている一方、Plugin 設計書本文には `battleWorldId` / `entryRadiusMeters` が残っている。実装後に設計書を正本として使うなら、命名をどちらかへ統一した方が後続実装の事故を減らせる。
