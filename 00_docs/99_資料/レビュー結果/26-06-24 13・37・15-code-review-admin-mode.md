# ADMIN mode 保存・通常プレイ導線 調査結果

## 概要

ADMIN mode のゲームモード・インベントリ保存/復元、および通常プレイで想定される NPC・GUI・インベントリアイテム処理の mode gate を調査した。

今回の調査ではソース修正は行っていない。後続の修正依頼で参照できるよう、修正が必要な箇所を `AR-CODE-*` の指摘 ID で整理する。

## 前提

- 対象プロジェクト: `10_plugin/AstralRecord`
- mode 判定の正本: `AccountMode.shouldProcessGameplay()` は `PLAYER` のみ true。
- ADMIN / BUILDER は tool mode として扱われ、通常プレイ導線からは原則除外する前提で調査した。
- Bukkit の `Player#getInventory()` / `Player#getGameMode()` 参照はメインスレッドで行う前提で修正案を記載する。

## 指摘一覧

### AR-CODE-001 [高] ADMIN / BUILDER の tool inventory snapshot metadata が永続化されない

対象:

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/state/PlayerInventoryState.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/state/InventoryPersistence.java`
- `10_plugin/AstralRecord/src/main/kotlin/io/github/maaasu/astralRecord/feature/inventory/repository/InventoryRepository.kt`

根拠:

- `InventoryService.saveToolInventorySnapshot(...)` は `InventoryProfile.BUILDER` / `InventoryType.NORMAL` の `metadataJson` に `admin` または `builder` key の snapshot を格納している。
- `PlayerInventoryState.updateInventoryMetadata(...)` は state を dirty にする。
- しかし `InventoryPersistence.save(...)` は inventory entries と loadout slots の保存のみを行い、親 inventory の `metadataJson` 変更を `InventoryRepository.updateMetadata(...)` へ送っていない。
- `InventoryRepository.updateMetadata(...)` は存在するが、現状の save path から呼ばれていない。

影響:

- ADMIN mode の現在の Bukkit inventory contents と game mode を state metadata に詰めても、API / DB へ保存されない。
- ログアウト、plugin disable、mode 切替時に保存したはずの ADMIN インベントリ・ゲームモードが、再ログインや再適用時に古い metadata から復元される。
- 「ADMIN のゲームモードやインベントリーアイテムが保存されていない」症状の主因と判断できる。

修正方針:

- `PlayerInventoryState` に inventory metadata の dirty tracking または persisted metadata snapshot を追加する。
- `InventoryPersistence.save(...)` で metadata が変更された inventory に対して `InventoryRepository.updateMetadata(inventoryId, metadataJson, updatedBy)` を呼ぶ。
- repository から返った `InventoryModel` で state 内の inventory を更新し、metadata dirty 状態を clear する。
- テストでは `saveToolInventorySnapshot -> InventoryPersistence.save -> InventoryRepository.updateMetadata` が呼ばれること、かつ `admin` key の game mode / contents が保存対象になることを確認する。

代替案:

- `saveToolInventorySnapshot(...)` 内で即座に `InventoryRepository.updateMetadata(...)` を呼ぶ。ただし既存の persistence 集約と異なるため、dirty save の一貫性を崩しやすい。

### AR-CODE-002 [中] AutoSave が ADMIN / BUILDER の現在の Bukkit inventory と game mode を capture しない

対象:

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryAutoSaveTask.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventorySaveTask.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java`

根拠:

- `InventorySaveTask.save(...)` は online player の通常保存時に tool mode なら `inventoryService.saveToolInventorySnapshot(player)` を呼ぶ。
- 一方で `InventoryAutoSaveTask.runSaveAll(...)` は `PlayerInventoryState` を列挙して `persistence.save(state, AUTO)` するだけで、online player の Bukkit inventory / game mode を snapshot していない。
- ADMIN / BUILDER の Bukkit inventory 編集は state に自動反映されず、`saveToolInventorySnapshot(...)` を呼んだ時点で初めて metadata へ反映される。

影響:

- AR-CODE-001 を修正しても、autosave では最新の ADMIN / BUILDER inventory と game mode が保存対象にならない。
- logout / disable / mode 切替では保存されるが、一定間隔 autosave による保護としては機能しない。

修正方針:

- autosave 前に online の `AstPlayer` を参照し、`BUILDER` / `ADMIN` の player についてメインスレッドで `saveToolInventorySnapshot(...)` を実行する。
- その後、既存どおり async persistence save を行う。
- Bukkit API 参照を async thread で行わないよう、capture と永続化の責務を分ける。

### AR-CODE-003 [高] 通常 NPC interaction が account mode を確認せず通常 GUI を開ける

対象:

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`

根拠:

- `onPlayerInteractEntity(...)`、ray-based の `onPlayerInteract(...)`、`onEntityDamageByEntity(...)` から `execute(...)` に入り、account mode を確認せず NPC action を処理している。
- `openGui(...)` は `SHOP`、`SELL`、`CLASS`、`STORAGE`、`EQUIPMENT_ENHANCE` など通常プレイ導線の GUI を開く。
- 比較対象として `TeleporterInteractEventHandler` は `mode.shouldProcessGameplay()` を確認しており、`GatheringService.startMining(...)` も同様に gameplay mode 以外を弾いている。

影響:

- ADMIN mode のまま NPC に interact でき、shop / storage / sell / enhancement / class などの GUI を開ける。
- GUI 側の mutation gate が不足している箇所と組み合わさると、ADMIN mode で通常インベントリや通貨への操作が可能になる。

修正方針:

- `MobInteractionEventHandler` の各 event entry point または `execute(...)` 冒頭で `AstPlayerCache` から `AstPlayer` を取得し、`astPlayer.getAccount().getMode().shouldProcessGameplay()` が false なら return する。
- event cancel のタイミングは UX 方針に合わせる。通常プレイ導線として完全無視するなら cancel 前 return、NPC をクリックしても通常動作を出したくないなら cancel 後 return とする。
- admin 用 NPC / spawner 編集などの tool 導線は、既存の admin 専用 handler 側に分離して許可する。

### AR-CODE-004 [中] 通常 GUI / command の入口で account mode gate が不足している

対象:

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/infrastructure/command/AstCommand.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/command/MenuCommand.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/command/ShopCommand.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/command/EnhanceCommand.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/party/command/PartyCommand.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/trade/command/TradeCommand.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`

根拠:

- `AstCommand` は player-only / permission は確認するが、account mode は共通チェックしていない。
- `/menu`、`/shop`、`/enhance`、`/party`、`/trade` は通常プレイ向け command だが、ADMIN mode を弾いていない。
- `MenuOpenEventHandler` は player inventory click や craft shortcut など一部で `PLAYER` check をしているが、menu GUI 自体の click dispatch や main menu から各画面への遷移は mode gate 前に処理される。

影響:

- NPC を経由しなくても ADMIN mode で通常 GUI を開ける。
- party / trade など player-to-player の gameplay state に ADMIN が参加できる可能性がある。
- menu 遷移から status / equipment / currency / party など通常画面へ入れる。

修正方針:

- 共通 helper を追加する。例: `AccountModeGuard.isGameplayPlayer(Player)` または `AstPlayerModeGuard.requireGameplay(AstPlayer)`。
- 通常プレイ専用 command の冒頭で `shouldProcessGameplay()` を確認する。
- `MenuOpenEventHandler.onInventoryClick(...)` は menu 系 inventory の dispatch 前に mode を確認し、PLAYER 以外なら close または ignore する。
- permission が ADMIN であることと account mode が ADMIN であることは別概念として扱い、debug / admin command の許可方針を明示する。

### AR-CODE-005 [中] Shop / Storage / Sell / Enhancement の mutation service が mode を前提条件として確認していない

対象:

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/event/ShopGuiEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/service/ShopService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/storage/service/StorageService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/sell/service/SellService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/equipmentEnhance/service/EquipmentEnhancementService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java`

根拠:

- `ShopGuiEventHandler.open(...)` / click 処理、`ShopService.purchase(...)` は mode を確認せず currency / inventory を更新する。
- `StorageService.open(...)` / `handleClick(...)` は mode を確認せず storage への移動・取り出しを実行する。
- `SellService.open(...)` / `handleClick(...)` は mode を確認せず売却候補への移動、売却、返却、gold 加算を実行する。
- `EquipmentEnhancementService.open(...)` / click / execute は mode を確認せず素材消費や強化結果保存を実行する。
- `InventoryService.moveDisplayedItemToStorage(...)`、`withdrawStorageEntry(...)`、`takeDisplayedItemAmount(...)` など低レイヤーの inventory mutation も、呼び出し元で mode が検証済みである前提になっている。

影響:

- AR-CODE-003 / AR-CODE-004 の入口漏れから GUI を開けると、ADMIN mode で通常 inventory / storage / currency を変更できる。
- 将来別の入口が追加された場合にも同じ問題が再発しやすい。

修正方針:

- 第一防衛線として、NPC / command / GUI open の入口で gameplay mode を必ず確認する。
- 第二防衛線として、各 GUI handler / service の public entry point でも `shouldProcessGameplay()` を確認する。
- 低レイヤーの `InventoryService` へ一律 gate を入れる場合は、admin tool 用の正当な inventory 操作まで塞がないよう、通常表示 inventory 操作用メソッドだけに限定する。

## 確認済みで低リスクの箇所

以下は今回の観点では mode gate が確認できた。

- `ItemInteractionBlockEventHandler`: item interact / consume / entity interact / damage の各 path で `AccountMode.PLAYER` を要求している。
- `ItemWeaponAttackEventHandler`: weapon attack で `AccountMode.PLAYER` 以外を return している。
- `SkillActionRingEventHandler`: skill ring 操作で `AccountMode.PLAYER` 以外を return している。
- `GatheringService.startMining(...)`: `shouldProcessGameplay()` で PLAYER 以外を return している。
- `TeleporterInteractEventHandler`: teleporter interaction で `shouldProcessGameplay()` 以外を return している。
- `PlayerModeEventHandler`: PLAYER mode の inventory click / drop / gamemode change を制限する handler であり、ADMIN を通常操作から弾く目的の handler ではない。

## 修正優先順

1. AR-CODE-001: metadata 永続化漏れを修正する。ADMIN inventory / game mode 保存不備の主因。
2. AR-CODE-003: NPC interaction を gameplay mode に限定する。現在の再現導線を直接塞ぐ。
3. AR-CODE-004: command / menu dispatch に gameplay mode gate を追加する。NPC 以外の入口を塞ぐ。
4. AR-CODE-005: shop / storage / sell / enhancement の service entry point へ防御的 gate を追加する。
5. AR-CODE-002: autosave 前の tool snapshot capture を追加する。保存保証を強化する。

## 後続修正タスクへの入力

- `AccountMode.shouldProcessGameplay()` を通常プレイ導線の判定基準として統一する。
- permission の ADMIN と account mode の ADMIN を混同しない。admin 権限を持つ PLAYER は通常 gameplay を許可し、account mode が ADMIN の player は通常 gameplay を弾く方針が自然。
- ADMIN / BUILDER の tool inventory は `InventoryProfile.BUILDER` の metadata に保存されているため、親 inventory metadata の persistence が必要。
- Bukkit inventory / game mode capture はメインスレッドで行い、API 保存は既存の async persistence に寄せる。
- GUI を閉じるか無視するか、block 時の player message を出すかは UX 方針を決めてから実装する。

## 未確認・要判断

- `/storage`、`/sell`、`/class gui` は現状 ADMIN permission command として実装されている。account mode が ADMIN のときにも使わせる debug 導線なのか、通常プレイ導線として PLAYER mode のみ許可するのか判断が必要。
- ADMIN mode で NPC をクリックした場合、event を cancel して無反応にするか、cancel せず Minecraft 標準挙動へ流すか判断が必要。
- autosave が tool inventory のクラッシュ直前保護まで保証する必要があるか判断が必要。ただし現状の「autosave」という名前からは保存対象に含める方が自然。

## 主な確認ファイル

- `10_plugin/AstralRecord/src/main/kotlin/io/github/maaasu/astralRecord/feature/account/model/AccountModel.kt`
- `10_plugin/AstralRecord/src/main/kotlin/io/github/maaasu/astralRecord/feature/player/model/AstPlayer.kt`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/service/PlayerService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/command/AccountModeCommand.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventorySaveTask.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryAutoSaveTask.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/state/InventoryPersistence.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/state/PlayerInventoryState.java`
- `10_plugin/AstralRecord/src/main/kotlin/io/github/maaasu/astralRecord/feature/inventory/repository/InventoryRepository.kt`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/event/ShopGuiEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/service/ShopService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/storage/service/StorageService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/sell/service/SellService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/equipmentEnhance/service/EquipmentEnhancementService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/event/ItemInteractionBlockEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/teleporter/event/TeleporterInteractEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/gathering/service/GatheringService.java`
- `00_docs/40_Database設計書/table-definitions/AstralRecord/dbo.inventory.md`
