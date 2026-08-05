# 25_3-GUI・View

## ウェイストーン表示同期

クラス名: `WaystonePacketView`
物理名: `syncForPlayer`

責務:

プレイヤーの現在ワールドと解除状態に応じて、ウェイストーンの BlockDisplay / TextDisplay / ItemDisplay 相当の packet 表示を同期する。

処理内容:

1. [[#ウェイストーン表示破棄]] で対象プレイヤーの既存 packet-only entity をすべて destroy する。
2. `AstPlayerCache` を取得できない場合は終了する。
3. 同一ワールド内で `Location` を解決できるウェイストーンだけを抽出する。
4. 対象プレイヤーの解除状態に応じて表示部品を構築し、すべて spawn する。
5. 生成した `PacketEntity` のリストをプレイヤー UUID ごとに保持する。

現行実装は表示距離による除外、既存 entity の metadata 更新、ウェイストーン単位の差分同期を行わない。

表示構成:

| 部品 | 表示 |
|:--|:--|
| 台座 | `DEEPSLATE_BRICKS` 相当の BlockDisplay |
| 本体 | `STONE_BRICKS` 相当の BlockDisplay |
| 装飾 | `POLISHED_ANDESITE` 相当の BlockDisplay |
| 頂部 | 解除済みは `SEA_LANTERN`、未解除は `REDSTONE_LAMP` 相当の BlockDisplay |
| 名称 | TextDisplay |
| アイコン | 名称の上に `icon`、未設定時は `BEACON` のドロップアイテム風 ItemDisplay |

TextDisplay:

| 状態 | 文言 |
|:--|:--|
| 解除済み | `&b<name>` |
| ロック中 | `未解除: <name>\n<unlockGold> ゴールド` |

## ウェイストーン表示破棄

クラス名: `WaystonePacketView`
物理名: `clearPlayer`

1. 対象プレイヤーに送信済みの packet-only entity ID を取得する。
2. destroy packet を送信する。
3. プレイヤー UUID に対応する `List<WaystonePacketDisplay.PacketEntity>` をキャッシュから削除する。

## テレポーター GUI 受付

クラス名: `TeleporterGuiEventHandler`
物理名: `open`

1. `InventoryService.setHotbarShortcutMode(astPlayer, true)` で共通ホットバーショートカットを有効にする。
2. [[#テレポーター GUI 表示]] を呼び出す。

## ウェイストーンからの GUI 表示音

クラス名: `TeleporterService`
物理名: `openGui`

ウェイストーン操作で `TeleporterGuiEventHandler.open` が完了して GUI を表示した場合だけ `GuiSound.OPEN` を一度再生する。GUI 内のページ移動は `TeleporterGuiEventHandler` が `GuiSound.PAGE` を再生するため、この経路で `OPEN` を重ねない。

## テレポーター GUI 表示

クラス名: `TeleporterGui`
物理名: `open`

責務:

起点ウェイストーンから移動可能な同一ワールド内ウェイストーン一覧を表示する。
この GUI は menu feature の画面遷移とは独立し、GUI 本体に戻るボタンを持たない。`TeleporterGui.Holder` は `HotbarShortcutGuiHolder` を実装し、共通ホットバーショートカットを利用する。

処理内容:

1. [[25_3-サービス]].GUI 表示項目構築 を呼び出す。
2. 54 slot inventory を作成する。
   - GUI 名称は起点ウェイストーン名のみを表示する。
3. slot `0-44` を一覧表示領域とする。
4. slot `45` を前ページ、slot `53` を次ページとする。
5. slot `46-52` はプレースホルダーとし、GUI 本体に戻るボタン・閉じるボタンを置かない。
6. `GuiOpenSupport.open` で inventory を開き、閉じる操作とインベントリ切替は共通ホットバー側で扱う。

ページング:

- 1 ページ最大 45 件。
- 前ページがない場合、slot `45` はプレースホルダーのままにする。
- 次ページがない場合、slot `53` はプレースホルダーのままにする。

## テレポーター GUI 再描画

クラス名: `TeleporterGui`
物理名: `render`

1. 現在ページの表示範囲を計算する。
2. 解除済み・未解除の両項目を、定義の `icon` で表示する。未設定時は `BEACON` を使用する。
3. 未解除状態は暗い表示名と lore で表現し、アイコン自体は解除済み項目と共通にする。
4. 解除済み項目の lore にはクリックで移動する旨を表示する。ワールド名や内部パスは表示しない。
5. 未解除項目の lore には未解除である旨と解除コストを表示する。
6. 制御行の slot `45-53` を `GRAY_STAINED_GLASS_PANE` で埋め、存在するページ操作だけ `MAP` へ差し替える。コンテンツ領域の空き slot は `AIR` のままとする。

解除済み項目:

| 項目 | 内容 |
|:--|:--|
| Material | 定義の `icon`。未設定時は `BEACON` |
| 表示名 | `&b<name>` |
| lore | `クリックで移動` |

未解除項目:

| 項目 | 内容 |
|:--|:--|
| Material | 定義の `icon`。未設定時は `BEACON` |
| 表示名 | `&7<name>` |
| lore | `未解除のウェイストーンです`、`必要ゴールド: <unlockGold>` |

## GUI 項目クリック解決

クラス名: `TeleporterGuiEventHandler`
物理名: `handleTopClick`

1. `TeleporterGui.Holder` から起点 ID、ページ番号、表示中 ID の並びを取得する。
2. slot `45` / `53` が有効なページ操作なら前後のページを開く。
3. slot `0-44` は `visibleWaystoneIds[rawSlot]` から対象定義を解決する。
4. 対象が未解除なら `P_5962` を通知して終了する。
5. 解除済みなら `TeleporterService.teleportToWaystone` へ委譲する。
6. Inventory click / drag はキャンセルし、プレイヤー inventory 側のクリックは `HotbarShortcutClickSupport` へ委譲する。
7. GUI close 時は `InventoryService.setHotbarShortcutMode(astPlayer, false)` で共通モードを解除する。
