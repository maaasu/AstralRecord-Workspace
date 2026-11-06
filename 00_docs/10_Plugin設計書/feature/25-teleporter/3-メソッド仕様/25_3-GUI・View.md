# 25_3-GUI・View

## ウェイストーン表示同期

クラス名: `WaystonePacketView`
物理名: `syncForPlayer`

責務:

プレイヤーの現在ワールドと解除状態に応じて、ウェイストーン本体と名称の packet 表示を同期する。Java 版には `LODESTONE` を使う固定 ItemDisplay を送り、リソースパックが `custom_model_data.strings[0]` に応じて石碑モデルを選ぶ。統合版には本体用の空 TextDisplay を送り、対応アセットを登録した接続で Geyser 拡張が `astralrecord:world_display` に変換する。

処理内容:

1. `AstPlayerCache` を取得できない、オフライン、または死亡中の場合は既存表示を破棄して終了する。
2. ワールド別の64 block幅の水平セル索引から周辺9セルを調べ、同一ワールドかつ基準座標から三次元距離64 block以内の定義を抽出する。チャンクはロードしない。
3. 表示用の解除状態は `TeleporterService.isUnlockedForView` で未受領解除を除外し、SQL ACK成功後だけ解除済みとして扱う。プレイヤー UUID・ウェイストーン ID ごとに、ワールド UUID、座標、名称、未解除時のコスト、アイコン、解除状態、Java/BE区分と送信済み entity 一式を保持する。
4. 新規対象だけ spawn する。表示状態が変わった石碑だけ既存一式を destroy して再生成し、変更のない石碑には packet を送らない。
5. 範囲外・別ワールド・削除済みの石碑だけ destroy する。表示が空になったプレイヤーのキャッシュは削除する。

近距離の差分同期は Bukkit メインスレッドで10 tick間隔に実行する。解除・作成・削除・再読み込み時も同じ差分同期を使う。定義索引は定義スナップショット公開時と作成・削除の保存成功後に再構築し、保存失敗時には変更しない。

ワールド移動時とリスポーン翌tickは、クライアントが表示を消去するため送信済み表示を明示的に破棄し、現在位置の近距離表示を復元する。通常移動・同一ワールドのテレポートは次の定期同期で反映する。

統合版の状態色付き粒子は同じ10 tick周期で差分同期後に送る。同期済みの近距離ウェイストーンを使い、基準座標上方にプレイヤー単位で1個表示する。対応パックが使えない接続では名称と粒子が表示される。

表示構成:

| 部品 | 表示 |
|:--|:--|
| 本体 | 石碑モデル。解除済みは青緑、未解除は赤橙の結晶と紋章 |
| 名称 | TextDisplay |
| アイコン | Java 版のみ。名称の上に `icon`、未設定時は `BEACON` のドロップアイテム風 ItemDisplay |

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
3. プレイヤー UUID に対応するウェイストーン単位の表示キャッシュを削除する。

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
