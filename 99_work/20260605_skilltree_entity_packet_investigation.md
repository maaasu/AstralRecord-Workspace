# スキルツリー Entity 増加疑いとパケット表示切替調査

作成日: 2026-06-05

## 結論

- `/skilltree` で入るスキルツリーワールドの表示は、現状 `Item` と `TextDisplay` の実体 Entity を常駐させ、`Player#showEntity` / `hideEntity` でプレイヤーごとの可視状態を切り替える設計です。
- `SkillTreeVisualizer` は 10tick ごとに既存 Entity を再利用するため、通常の tick 更新だけで無限に増える構造ではありません。
- ただし、ノード数と接続距離に比例してサーバ実体 Entity が増える設計です。接続線は色状態ごとに 4 系統の `TextDisplay` を全点分生成するため、接続が長いほど急増します。
- 提示ログの停止箇所は Purpur/Moonrise のチャンクアンロード中 `ChunkEntitySlices.removeEntity` です。スキルツリーワールド離脱時に、大量の表示 Entity を含むチャンクがアンロードされ、Entity 削除処理で詰まった可能性があります。
- 現在確認できた実サーバの `skilltree_structure.yml` は position 2 件、edge 1 件のみで、保存済み `entities` ファイルも 0 件でした。この現時点の構造だけなら大量 Entity の直接原因とは言い切れません。
- ただし、将来スキルツリーを本格配置した場合、現在の設計はノード・接続・プレイヤー数に対して重くなりやすいため、スキルツリー表示、頭上表示、ダメージ表示、ドロップ演出、アクションリング、スポナー可視化はパケット表示共通基盤へ寄せるのが妥当です。

## 調査対象

- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\skilltree\service\SkillTreeService.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\skilltree\service\SkillTreeVisualizer.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\shared\display\DisplayTextService.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\shared\display\OverheadDisplayService.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\item\service\ItemDropAnimationService.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\mob\service\MobDropPresentationService.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\skill\service\SkillActionRingService.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\spawner\service\MobSpawnerVisualizer.java`
- `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\temp\command\TempCommand.java`
- 実サーバ: `\\DEVICE_SERVER\server\CraftyController\crafty-__saas-windows-medium-amd64__-_03629d64\servers\5bf4f70b-2c02-4a6b-b23f-8453237d2d97\plugins\AstralRecord\skilltree_structure.yml`

## スキルツリー表示の実装

`SkillTreeService.start()` は起動時に `purgeSkillTreeVisualEntities()` を呼び、スキルツリーワールド内の `Item` / `TextDisplay` を削除してから `SkillTreeVisualizer` を開始します。該当箇所は `SkillTreeService.java:184` と `SkillTreeService.java:896` 以降です。

`SkillTreeVisualizer` は `INTERVAL_TICKS = 10` で定期実行されます。position と edge を Map で保持し、既存 visual が利用可能なら再利用、消えた・ワールドが変わった・点数が合わない場合のみ remove して再生成します。該当箇所は `SkillTreeVisualizer.java:37`、`SkillTreeVisualizer.java:56`、`SkillTreeVisualizer.java:72` 以降、`SkillTreeVisualizer.java:108` 以降です。

### position あたりの Entity

`createPositionVisual()` で管理者向けに以下を生成します。

- `Item` x 1
- `TextDisplay` x 2

ノード定義がある position では `ensureNodeDisplays()` によりプレイヤー向けに以下が追加されます。

- locked `Item` x 1
- unlocked `Item` x 1
- locked `TextDisplay` x 1
- unlocked `TextDisplay` x 1

つまり、ノード定義なし position は 3 Entity、ノード定義あり position は 7 Entity です。該当箇所は `SkillTreeVisualizer.java:133` から `SkillTreeVisualizer.java:137`、および `SkillTreeVisualizer.java:397` から `SkillTreeVisualizer.java:400` です。

### edge あたりの Entity

`createEdgeVisual()` は接続線の中間点ごとに、管理者用 purple、未解放 gray、片側解放 white、両側解放 yellow の 4 種類の `TextDisplay` を生成します。点数は `ceil(distance / 0.45) - 1` です。該当箇所は `SkillTreeVisualizer.java:176` から `SkillTreeVisualizer.java:187`、`SkillTreeVisualizer.java:267` です。

概算式:

```text
position Entity 数 = 未定義 position 数 * 3 + 定義済み position 数 * 7
edge Entity 数 = Σ((ceil(edge distance / 0.45) - 1) * 4)
総数 = position Entity 数 + edge Entity 数
```

今回の実サーバ構造は position 2 件、edge 1 件です。`root_001` と `root_002` はどちらも filebase に定義があり、距離は約 2.83 block なので、概算は以下です。

```text
position: 2 * 7 = 14
edge: (ceil(2.83 / 0.45) - 1) * 4 = 24
合計: 約 38 Entity
```

この 38 件程度では、単独で 10 秒停止を起こす規模には見えません。ただし、大規模な構造に増えると接続線が支配的になります。例えば 100 ノード、150 edge、平均距離 6 block なら、position 約 700、edge 約 7,800、合計約 8,500 Entity になります。

## 時間とともに増加するか

コード上、通常 tick ごとに Entity を追加し続ける明確な増殖ループは見つかりませんでした。

- position は `positions` Map で positionId ごとに 1 visual を保持します。
- edge は `edges` Map で edge key ごとに 1 visual を保持します。
- active でなくなった position / edge は remove されます。
- `stop()` 時も全 visual を remove します。

ただし、以下のリスクがあります。

- `setPersistent(false)` であっても、サーバ停止・クラッシュ・プラグイン停止順・ワールド保存タイミングによって残留する可能性があるため、`purgeSkillTreeVisualEntities()` でワールド内 Entity と `entities` ディレクトリを掃除する実装が入っています。
- `purgeSkillTreeVisualEntities()` は `Item` / `TextDisplay` を無差別削除します。スキルツリーワールド専用なら成立しますが、将来同ワールドに別用途の表示 Entity を置くと巻き込みます。
- `viewerStates` はプレイヤー UUID ごとに増えます。Entity 数そのものではありませんが、過去に近づいたプレイヤー分の状態が残り続けます。オンラインプレイヤー限定で更新されるため即時の Entity 増殖ではないものの、長期稼働では掃除対象です。

## エラーとの対応

提示ログのスタックは以下の流れです。

```text
ChunkEntitySlices.removeEntity
EntityLookup.removeEntity
Entity.setRemoved
PaperHooks.unloadEntity
ChunkEntitySlices.unload
NewChunkHolder.unloadStage2
ChunkHolderManager.processUnloads
ServerLevel.tick
```

これはプラグインコード内で例外が出た形ではなく、チャンクアンロードに伴う Entity remove/unload がサーバスレッドで詰まっています。`/skilltree` 移動後に発生したという状況からは、拠点ワールド側またはスキルツリーワールド側のチャンクロード/アンロード時に大量 Entity を処理した可能性があります。

現時点の `skilltree_structure.yml` ではスキルツリー表示 Entity は約 38 件なので、今回ログの直接原因としては弱いです。一方、設計としてはスキルツリー規模が増えるほど常駐 Entity が増えるため、同じ種類の停止を将来的に誘発しやすいです。

実サーバ確認:

- `skilltree_structure.yml`: position 2 件、edge 1 件
- `plugins\AstralRecord\worlds\hub\skill_tree\entities`: 保存ファイル 0 件

## パケット表示へ切り替える候補

優先度 A: 早めにパケット化したい箇所

- `SkillTreeVisualizer`: スキルツリーの `Item` / `TextDisplay` / 接続線。構造規模に比例して常駐 Entity が増える。接続線は特に増えやすい。
- `DisplayTextService`: 汎用 TextDisplay 基盤。ダメージ数値、結果表示、バフ通知、頭上表示がここへ集約されており、ここをパケット実装に差し替える効果が大きい。
- `OverheadDisplayService`: プレイヤー・Mob ごとに `TextDisplay` を持つ。プレイヤー数と Mob 数に比例して常駐 Entity が増える。
- `ItemDropAnimationService` / `MobDropPresentationService`: ドロップ演出を `ItemDisplay` / `TextDisplay` で生成し、viewer だけに show している。まさにパケット表示向き。
- `SkillActionRingService`: プレイヤーごとに短時間で `ItemDisplay` と `TextDisplay` を大量生成する。現在は 1 セッションあたり `SLOT_COUNT` icon + `SLOT_COUNT` label + 24 dots + timer なので、SLOT_COUNT が 8 なら 41 Entity。

優先度 B: 追ってパケット化したい箇所

- `MobSpawnerVisualizer`: 管理者向けに `BlockDisplay` + `TextDisplay` を生成し、show/hide している。管理者専用表示なのでパケット向き。
- `TempCommand`: `/temp` の drop / BlockDisplay 表示。検証用・一時表示だが、実体表示のサンプルとして残ると設計が分散する。

優先度 C: パケット表示と実体 Entity を分けて判断する箇所

- `BundleUseService` の `dropItemNaturally`: これはプレイヤーが実際に拾う/落ちるアイテムなら実体 Entity が妥当です。単なる演出ならパケット表示へ寄せます。
- `MobEntityController`: Mob 本体はゲームロジック上の実体 Entity が必要なので、今回のパケット表示対象外です。
- `ParticleDisplayService`: 既に `World#spawnParticle` / `Player#spawnParticle` の共通口です。Particle は Bukkit API 自体がパケット送信相当ですが、全体の表示基盤として viewer 選択・密度・距離制御をここに寄せるのは妥当です。

## 共通基盤案

新規に `shared.packetdisplay` などのパッケージを用意し、表示を次の抽象で統一するのが合理的です。

### 1. PacketDisplayService

役割:

- viewer 単位で仮想 Entity ID を払い出す。
- spawn / metadata / equipment / teleport / destroy パケットを送る。
- viewer のワールド変更、ログアウト、距離外、表示終了時に destroy を保証する。
- 表示更新 tick を一元管理し、各 feature が独自 `runTaskTimer` と Entity Map を持たないようにする。

想定 API:

```java
PacketDisplayHandle spawnText(Player viewer, Location location, PacketTextDisplayOptions options);
PacketDisplayHandle spawnItem(Player viewer, Location location, ItemStack item, PacketItemDisplayOptions options);
PacketDisplayHandle spawnBlock(Player viewer, Location location, BlockData block, PacketBlockDisplayOptions options);
void destroyAll(Player viewer);
```

### 2. PacketDisplayScene

スキルツリーやアクションリングのように複数表示をまとめる単位です。

- sceneId: `skilltree:<playerUuid>` / `action-ring:<playerUuid>` など
- owner/viewer: 表示対象 player
- handles: 複数の text/item/block 表示
- update: 位置・テキスト・アイテム差し替え
- close: 全 destroy

### 3. 表示種別の共通オプション

- Text: text, billboard, seeThrough, shadow, background, lineWidth, scale, viewRange
- Item: itemStack, billboard, scale, brightness
- Block: blockData, transformation, brightness
- Particle: 既存 `ParticleDisplayService` と連携し、viewer 指定と密度制御を統一

### 4. 移行方針

1. `DisplayTextService` の内部実装をパケット版へ差し替える。
2. `ItemDropAnimationService` と `MobDropPresentationService` を PacketDisplayScene に移行する。
3. `SkillActionRingService` を player 専用 scene として移行する。
4. `SkillTreeVisualizer` を「ワールド常駐 Entity」ではなく「近距離 viewer ごとの scene」に変更する。
5. `MobSpawnerVisualizer` と `TempCommand` を最後に移行し、実体 Display の新規利用を禁止するルールを追加する。

## スキルツリー移行時の設計メモ

- position/edge の構造はサーバ側に保持するが、表示は viewer ごとに生成する。
- viewer がスキルツリーワールドに入り、距離内にある position/edge のみ PacketDisplayScene に出す。
- edge は 4 色分の実体を全生成せず、viewer の状態に応じた 1 色だけを送る。
- position も admin / locked / unlocked の全実体を持たず、viewer に必要な item/text のみ送る。
- viewer 状態が変わったときは metadata/equipment 更新で差し替え、不要になった表示は destroy パケットを送る。

これにより、現在のサーバ実体 Entity 数は 0 にでき、表示数も「全状態分」ではなく「その viewer に必要な状態分」にできます。

## 追加で確認したいこと

- エラー発生直後の spark profiler または entity count dump。
- `/skilltree` 実行前後の各ワールド Entity 数、特に `Item`, `TextDisplay`, `ItemDisplay`, `BlockDisplay`。
- スキルツリーワールドが実際にアンロードされるタイミングと view-distance / simulation-distance。
- 今回ログ時点の `skilltree_structure.yml` が現在と同じ 2 position / 1 edge だったか。
- 拠点ワールドに長時間放置で増える Entity がないか。今回の stack は「移動先」だけでなく「離脱元チャンクのアンロード」でも説明できます。

## 追記: `/say @e` 出力解析

ユーザー提供の `/say @e` 出力を集計したところ、以下の Entity 名が確認されました。

```text
Text Display: 1572
Iron Sword: 188
Armor Stand: 102
Text: 1
Total: 1863
```

この構成は `SkillTreeVisualizer` の実体 Entity 構造と強く一致します。

- `Armor Stand` は `createPositionVisual()` が生成する管理者用 `Item` の中身名と考えられます。つまり position 数は約 102 件です。
- `Iron Sword` は `ensureNodeDisplays()` が生成する locked/unlocked のノード表示 `Item` と考えられます。ノード定義済み position では 2 個生成されるため、188 / 2 = 約 94 ノード分です。
- `Text Display` は position 表示のテキストと edge 表示の接続線テキストです。position 由来を概算すると、管理者用 102 * 2 = 204、ノード用 94 * 2 = 188、合計 392 件です。
- 残りの TextDisplay は 1572 - 392 = 1180 件で、edge 表示由来と考えられます。edge は 1 点につき 4 色分を生成するため、1180 / 4 = 約 295 点分の接続線です。

このため、今回の `/say @e` 出力は「スキルツリー実体 Entity が大量に存在する」ことを裏付ける材料です。先に確認した実サーバの `skilltree_structure.yml` は position 2 件 / edge 1 件でしたが、`/say @e` の結果はそれと矛盾します。考えられる理由は以下です。

- `/say @e` を実行したサーバまたはワールドが、調査時に確認した `skilltree_structure.yml` と異なる。
- 調査後または調査前の別時点で、position/edge が多い構造ファイルが使われていた。
- `setPersistent(false)` の表示 Entity が何らかの停止・アンロード順・掃除漏れでワールド上に残留している。
- `/say @e` がスキルツリーワールド以外の実体表示も含めている。ただし `Armor Stand` と `Iron Sword` の比率はスキルツリー position/node 表示とかなり整合します。

この追記を踏まえると、スキルツリー表示は「時間で無限増殖する」と断定するより、「構造規模分の実体 Entity を生成し、それがワールド上に大量常駐または残留している」と見るのが妥当です。パケット表示への切替優先度は `SkillTreeVisualizer` が最優先です。

## 実装反映: スキルツリー表示のパケット化

2026-06-05 に、最優先対象である `SkillTreeVisualizer` をサーバ実体 Entity 方式から viewer ごとのパケット表示方式へ切り替えました。

変更内容:

- `shared.packetdisplay.PacketDisplayService` を追加し、ProtocolLib で仮想 `TextDisplay` / `ItemDisplay` を viewer 単位に spawn / metadata / teleport / destroy できるようにした。
- `SkillTreeVisualizer` は `Item` / `TextDisplay` を `World#spawn(...)` しない実装に変更した。
- position / edge の構造はサーバ側の既存データを使い続けるが、表示はオンライン player ごとの `ViewerScene` にだけ生成する。
- edge は従来の 4 色分を全生成する方式をやめ、viewer の状態に応じた 1 色だけを送る。
- 起動時の `purgeSkillTreeVisualEntities()` は残し、過去実装で残った `Item` / `TextDisplay` の掃除を継続する。

実装後のスキルツリー表示では、`/say @e` にスキルツリー表示用の `Text Display` / `Armor Stand` / `Iron Sword` が常駐 Entity として増える設計ではなくなります。表示はクライアントへ送信した仮想 Entity であり、viewer が範囲外・別ワールド・ログアウトになった場合は destroy パケットで閉じます。

未対応:

- `DisplayTextService`、`OverheadDisplayService`、`ItemDropAnimationService`、`MobDropPresentationService`、`SkillActionRingService`、`MobSpawnerVisualizer`、`TempCommand` は今回の実装範囲外。順次 `shared.packetdisplay` へ寄せる候補として残る。
- パケット metadata の index は Paper/ProtocolLib 1.21.11 系を前提にしているため、Minecraft minor 更新時は実機表示確認が必要。
