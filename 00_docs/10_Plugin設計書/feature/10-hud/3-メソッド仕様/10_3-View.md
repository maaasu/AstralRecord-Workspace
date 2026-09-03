# 10_3-View

## 1. status ActionBar 描画

クラス名: `PlayerHudView`
物理名: `renderActionBar`

HP、MP、ENG の現在値 / 最大値を表示し、`MAX_SHIELD > 0` の場合だけ SH を追加する。Shield リチャージ中も現在 Shield / 最大値を維持し、SH の後ろへオレンジ色の `(RC 残り秒数)` を0.1秒単位で併記する。有効な状態異常は固定優先順位で最大3件を右側へ併記し、種別ごとの色、太字の識別記号・表示名、残り秒数で装飾する。4件目以降は `+N` で省略数を示し、リチャージ中の残り時間とSH値を優先する。

## 2. 一時 ActionBar 描画

クラス名: `PlayerHudView`
物理名: `renderDodgeWindowActionBar`, `renderWallClingActionBar`

残り割合を 0..1 へ clamp し、28 文字の `DODGE` または `WALL` progress bar を表示する。

## 3. vanilla bar 描画

クラス名: `PlayerHudView`
物理名: `renderBars`

| status | Bukkit 表示 |
|---|---|
| HP / MAX_HEALTH | health attribute の最大値を 20 に保ち、health 0.5..20 へ写像 |
| ENG / MAX_ENERGY | food level 0..20、saturation 0 |
| MP / MAX_MANA | `MAX_ABSORPTION` を 20 に保ち、absorption 0..20 へ写像。最大10個の黄色いハートで表示。独自HP / Shieldダメージでは消費しない |
| account EXP / player level | Paper の経験値表示パケットへレベル内進捗とアカウントプレイヤーレベルを渡す。vanilla経験値の実値は変更しない |
| shield / MAX_SHIELD | armor attribute 0..20。リチャージ中も現在 Shield / 最大値の比率を使用 |

## 4. sidebar 描画・解除

クラス名: `PlayerHudView`
物理名: `renderSidebar`, `removeSidebar`

plugin 専用 objective `astral_info` を player scoreboard に作成・再描画する。プレイヤーはレベル値のみを表示し、クラス名・クラスレベルの直下に現在クラスの `EXP` 進捗バー、その直下に所持金を `Gold: {値}G` 形式で表示する。skilltree ワールド用の CP 表示名と CP / PP 残高が渡された場合は、Gold の直下に `CP[現在クラス表示名]: {未使用CP} / PP: {未使用PP}` を1行で表示する。経験値進捗バーは上下幅の小さいブロック文字 `▰` を10文字使用する。エリア名は `エリア`、エリアレベルは `エリアレベル`、通信遅延は `PING` と表示する。performance 表示が有効な場合だけ MSPT / PING を含め、挑戦中の boss または dungeon がある場合は名称、death、進行時間、参加者を追加する。参加者名は名前の途中で分割せず、1行20文字を目安に折り返し、2行目以降は参加者ラベル位置に揃えて表示する。15行の上限で全参加者を表示できない場合は、名前を無言で切り捨てず最終行へ `…ほかN人` を表示する。準備中は `P_6713`／`P_6714` の待機状態を追加し、Hub 未到着の参加者名を灰色にする。ただし、ハブ初期スポーンから待機離脱した `waitingAbsent` 本人には挑戦 Sidebar を返さない。`BUFF_SIDEBAR_DISPLAY` が有効な場合は、有効バフを獲得順に最大5件、buff / debuff の色分け、残り時間付きで追加する。超過分は最後の表示行へ「ほかN件」と付記する。折り返しで増える参加者行も含めて全体は15行以内とし、必要時は performance 情報を省略し、boss または dungeon 表示中は参加者とボス／ダンジョン名の必須行を先に確保したうえで、利用可能行数まで挑戦任意情報とバフ件数を縮小する。非 gameplay mode では同 objective だけを unregister する。

## 5. tab list 描画

クラス名: `PlayerHudView`
物理名: `renderTabList`

header に `ASTRAL RECORD` を表示し、performance 表示が有効な場合だけ MSPT と ping を色分けして追加する。

各プレイヤーのリスト名は、ログインデータ反映時・クラス変更時・クラスレベル変更時・クラスマスター再読込時・AFK状態遷移時に `PlayerClassService.updatePlayerListName` が更新し、クラス短縮名 `shortName` と現在クラスレベルを `[短縮名 Lv.レベル]` のタグとしてアカウント名の左へ表示する。`shortName` に定義した色・装飾コードを反映する。AFK中は、その左に赤い`[AFK]`接頭辞を付与する。
