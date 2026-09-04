# Plugin feature カタログ

この表は、設計書の機能境界と `10_plugin/AstralRecord` の実装責務を対応付ける正本である。複数 feature が同じ実装を参照する場合でも、主所有者はこの表の「主対象 package」に従う。

| No. | 設計 feature | 主な責務 | 主対象 package / 境界 |
|:--|:--|:--|:--|
| 01 | [[01_0-概要]] | Minecraft ユーザー登録、権限、履歴 | `feature/user` |
| 02 | [[02_0-概要]] | 選択アカウント、モード、アカウント進行 | `feature/account` |
| 03 | [[03_0-概要]] | プレイヤーライフサイクル、チャット、DiscordSRV連携、死亡、ログインボーナス、クラス進行、ホワイトリスト、権限別接続人数制限 | `feature/player`, `feature/discord`, `feature/loginbonus`, `feature/class`, `feature/playerclass`, `feature/whitelist` |
| 04 | [[04_0-概要]] | アイテム定義、生成、使用、装備操作 | `feature/item` |
| 05 | [[05_0-概要]] | バフ定義と適用状態 | `feature/buff` |
| 06 | [[06_0-概要]] | ルートテーブル取得と抽選 | `feature/loot` |
| 07 | [[07_0-概要]] | プレイヤーステータス計算と表示 | `feature/status` |
| 08 | [[08_0-概要]] | インベントリ同期、装備、保管庫 | `feature/inventory`, `feature/storage` |
| 09 | [[09_0-概要]] | 共通メニュー、ガイド、売却導線 | `feature/menu`, `feature/guide`, `feature/sell`, `shared/gui` |
| 10 | [[10_0-概要]] | HUD の組み立てと表示 | `feature/hud` |
| 11 | [[11_0-概要]] | プレイヤー設定と設定 GUI | `feature/playersetting`, `feature/particle` |
| 12 | [[12_0-概要]] | Mob、NPC、採集、スポナー、固定 text display | `feature/mob`, `feature/gathering`, `feature/spawner`, `feature/textdisplay`, `shared/display` |
| 13 | [[13_0-概要]] | スキル発動、bind、skill tree | `feature/skill`, `feature/skilltree` |
| 14 | [[14_0-概要]] | ダメージ計算と戦闘状態 | `feature/combat` |
| 15 | [[15_0-概要]] | ホットバー入力と item / skill action の調停 | item・inventory・skill が 28 の共通入力調停へ候補を提供する依存境界 |
| 16 | [[16_0-概要]] | 通貨残高と両替 | `feature/currency` |
| 17 | [[17_0-概要]] | WorldMasterData、ワールド遷移、スポーン | `feature/world`, `shared/teleport`, `shared/effect` |
| 18 | [[18_0-概要]] | メール一覧、既読化、報酬受取 | `feature/mail` |
| 19 | [[19_0-概要]] | パーティー状態と Mob 報酬共有 | `feature/party` |
| 20 | [[20_0-概要]] | ショップ表示、コスト preview、購入補償 | `feature/shop` |
| 21 | [[21_0-概要]] | Mob 討伐の冒険記録と閲覧 GUI | `feature/adventurerecord` |
| 22 | [[22_0-概要]] | プレイヤー間トレードと返却補償 | `feature/trade` |
| 23 | [[23_0-概要]] | マーケット出品・検索・取引 | `feature/market` |
| 24 | [[24_0-概要]] | Minecraft からの Web ログインコード発行 | `feature/webauth` |
| 25 | [[25_0-概要]] | ウェイストーン、解除状態、同一ワールド転送 | `feature/teleporter` |
| 26 | [[26_0-概要]] | ボス挑戦、専用フィールド、終了処理 | `feature/boss` |
| 27 | [[27_0-概要]] | 状態異常の付与、tick、解除 | `feature/condition` |
| 28 | [[28_0-概要]] | 複数機能のクリック候補を一件へ調停 | `shared/interaction` の共通契約 |
| 29 | [[29_0-概要]] | クエスト状態、進捗、報酬、board | `feature/quest` |
| 30 | [[30_0-概要]] | Java 用リソースパック要求と client status | `feature/resourcepack` |
| 31 | [[31_0-概要]] | 検証用カカシの配置、共有ステータス調整、非致死・定期回復 | `feature/trainingdummy` |
| 32 | [[32_0-概要]] | BSP ダンジョン生成、部屋戦闘、ゲート進行、一時ワールド回収 | `feature/dungeon` |

## 更新規則

1. `feature/<package>` を追加したら、主所有者となる設計featureをこの表に割り当て、「実装所有パス」へ記載する。
2. 一つの実装 package を複数の設計 feature が参照する場合、主所有者以外は依存境界だけを記載し、同じ仕様を複製しない。
3. 実装 package の移動、統合、削除と同じ変更でこの表を更新する。
4. 独立した責務を既存 feature に収められない場合は、一意な次番号で設計 feature を追加する。

## 実装所有パス

各featureが設計上所有する実装・resourceパスを記載する。複数featureにまたがる実装の主所有者は、上の一覧とこの節を正本として判断する。

### [[01_0-概要|01-user]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/user/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/user/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/user/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/user/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/user/model/*`

### [[02_0-概要|02-account]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/model/*`

### [[03_0-概要|03-player]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/death/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/save/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/discord/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/loginbonus/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/class/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playerclass/*`

### [[04_0-概要|04-item]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/executor/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/gui/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/view/*`

### [[05_0-概要|05-buff]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/buff/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/buff/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/buff/model/*`

### [[06_0-概要|06-loot]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/loot/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/loot/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/loot/model/*`

### [[07_0-概要|07-status]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/service/*`
- `10_plugin/AstralRecord/src/main/resources/player.properties`（P_5100 から P_5108）

### [[08_0-概要|08-inventory]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/state/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/storage/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（inventory 固有 `W_5250` から `W_5257`、shared GUI の `E_5601`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`

### [[09_0-概要|09-menu]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/player/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/view/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/guide/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/sell/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/storage/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/gui/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（`E_5600`、shared GUI の `E_5601`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5601` から `P_5603`）

### [[10_0-概要|10-hud]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/hud/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/hud/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/hud/service/PlayerHudService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/hud/service/AdminMessageBossBarService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/hud/view/PlayerHudView.java`
- `10_plugin/AstralRecord/src/main/resources/player.properties`（管理者メッセージ `P_6910` から `P_6912`）

### [[11_0-概要|11-player-setting]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/cache/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/gui/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/OptimisticLockConflictException.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/PlayerSettingMsgId.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/particle/command/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（`W_5310` から `W_5312`、`E_5310` から `E_5314`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5320` から `P_5326`）

### [[12_0-概要|12-mob]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/spawner/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/textdisplay/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/gathering/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/display/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（Mob の `5700` 系、採集配置の shared `E_6400` / `E_6401`、採集 packet の shared `W_9010`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（Mob / NPC / TextDisplay / gathering の player message）

### [[13_0-概要|13-skill]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skilltree/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（skill 固有 `5800` 系、shared event の `E_3002`、shared GUI の `E_5601`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5800` から `P_5811`、`P_5848`、`P_5849`）

### [[14_0-概要|14-combat]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（`E_5900`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5350` から `P_5353`）

### [[15_0-概要|15-hotbar-action]]

独立した実装 package は存在しない。共通入力調停の主所有者は [[28_0-概要|28-player-interaction]] とし、本 feature は次の依存 feature が候補を提供する境界だけを扱う。

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/event/ItemWeaponAttackEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/ItemWeaponAttackService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/*`

### [[16_0-概要|16-currency]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/currency/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`

### [[17_0-概要|17-world]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/world/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/teleport/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/effect/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/timing/*`

入力候補の共通調停には [[28_0-概要|28-player-interaction]] を依存先として使用する。

### [[18_0-概要|18-mail]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mail/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/MenuOpenEventHandler.java`

### [[19_0-概要|19-party]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/party/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java`

### [[20_0-概要|20-shop]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/currency/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`

### [[21_0-概要|21-adventurerecord]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/adventurerecord/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/*`

### [[22_0-概要|22-trade]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/trade/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/*`

金額入力 GUI は [[09_0-概要|09-menu]] が所有する `shared/gui/gold/*` を利用する。

### [[23_0-概要|23-market]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/market/*`

### [[24_0-概要|24-web-auth]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/webauth`

### [[25_0-概要|25-teleporter]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/teleporter/*`
- Plugin data folder: `waystones.yml`

### [[26_0-概要|26-boss]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/gui/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/view/*`
- `10_plugin/AstralRecord/src/main/resources/config.yml`
- `10_plugin/AstralRecord/src/main/resources/player.properties`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`

### [[27_0-概要|27-condition]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/display/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/task/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`
- `10_plugin/AstralRecord/src/test/java/io/github/maaasu/astralRecord/feature/condition/*`

### [[28_0-概要|28-player-interaction]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/interaction/*`

各業務候補を生成する `PlayerInputResolver` 実装は、依存 feature 側のイベント設計書を正本とする。

### [[29_0-概要|29-quest]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/quest/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/gathering/service/GatheringService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/event/PlayerJoinEventHandler.java`

### [[30_0-概要|30-resource-pack]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/resourcepack/*`
- `10_plugin/AstralRecord/src/main/resources/config.yml`
- `10_plugin/AstralRecord/src/main/resources/player.properties`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`

### [[31_0-概要|31-training-dummy]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/trainingdummy/*`
- Plugin data folder: `training-dummies.yml`

### [[32_0-概要|32-dungeon]]

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/dungeon/*`
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_7000` から `P_7047`）
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（Dungeon の `7000` 系）
- `40_filebase/65.features.dungeon/*`

## 共通基盤所有パス

次の package は単一の業務 feature に所有させず、`PLUGIN_GUIDE.md` の共通規約を設計正本とする。各 feature は必要な契約だけを依存境界として記載する。

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/infrastructure/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/challenge/*`（Boss／Dungeon 共用の開始 countdown・死亡許容判定・インスタンス作成枠キュー）

feature 固有 resource の ID 範囲や利用条件は各 feature が所有し、共通 loader、resolver、logger、正規化処理の実装境界はこの共通基盤が所有する。
