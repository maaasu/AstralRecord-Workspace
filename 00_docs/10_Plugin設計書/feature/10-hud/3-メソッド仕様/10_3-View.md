# 10_3-View

## 1. status ActionBar 描画

クラス名: `PlayerHudView`
物理名: `renderActionBar`

HP、MP、ENG の現在値 / 最大値を表示し、ガードコンバートのバインド中は現在値が0でもGUARD、通常Shieldまたは一時Shieldの表示上限が正数の場合だけSHを追加する。通常Shieldの表示上限は`MAX_SHIELD`、有効な一時Shieldは付与量とし、`MAX_SHIELD` を持つがタンクシールドアクティベートが無効なプレイヤーでも一時Shieldの付与量を優先する。HP、MP、ENG、GUARD、SH、状態異常要約、DPSの各要素は`〚`と`〛`で囲み、要素間を半角空白で分離する。Shield リチャージ中も現在 Shield / 最大値を維持し、SH の内側へオレンジ色の `(RC 残り秒数)` を0.1秒単位で併記する。有効な状態異常は固定優先順位で最大3件を右側へ併記し、種別ごとの色、太字の識別記号・表示名、残り秒数で装飾する。4件目以降は `+N` で省略数を示し、リチャージ中の残り時間とSH値を優先する。

管理配布パックを適用した Java 版では HP、MP、ENG の囲みとラベルを専用アイコンと10段階のゲージに切り替える。アイコンと現在値の間には数値の桁数にかかわらず半角空白を挿入する。HP が25%以下の場合は、10 tick 間隔の通常更新でゲージ色を交互に切り替える。統合版のプレートと装飾のアニメーションはクライアントで描画する。

DPSは `COMBAT_DPS_DISPLAY` がtrueの場合だけ末尾へ追加する。従来の引数形式ではDPSを表示する。

不死鳥との共鳴が有効で、phoenixの現在値が0より大きい場合だけ、金色の `PHOENIX 現在値 / 最大値` を追加する。現在値0の未召喚・消滅後は表示しない。

## 2. 一時 ActionBar 描画

クラス名: `PlayerHudView`
物理名: `renderDodgeWindowActionBar`, `renderWallClingActionBar`

残り割合を 0..1 へ clamp し、HP、10文字の `DODGE` または `WALL` progress bar、通常と同じ優先順位・最大3件の状態異常要約を表示する。受付期間・更新周期は変えない。ステータス引数を持たない互換呼び出しでは従来の28文字バーだけを表示する。

## 3. vanilla bar 描画

クラス名: `PlayerHudView`
物理名: `renderBars`

| status | Bukkit 表示 |
|---|---|
| HP / MAX_HEALTH | health attribute の最大値を 20 に保ち、health 0.5..20 へ写像 |
| ENG / MAX_ENERGY | food level 0..20、saturation 0 |
| MP / MAX_MANA | `MAX_ABSORPTION` を 20 に保ち、absorption 0..20 へ写像。最大10個の黄色いハートで表示。独自HP / Shieldダメージでは消費しない |
| account EXP / player level | Paper の経験値表示パケットへレベル内進捗とアカウントプレイヤーレベルを渡す。vanilla経験値の実値は変更しない |
| shield / 表示上限 | armor attribute 0..20。通常Shieldは`MAX_SHIELD`、有効な一時Shieldは付与量を上限にして現在 Shield との比率を使用する。リチャージ中も通常Shieldの現在値 / 最大値の比率を使用 |

## 4. sidebar 描画・解除

クラス名: `PlayerHudView`
物理名: `renderSidebar`, `removeSidebar`

plugin 専用 objective `astral_info` を player scoreboard に作成・再描画する。オンライン人数、ワールド、エリア、エリアレベルを基本項目とする。performance 表示が有効な場合だけ MSPT / PING を含め、挑戦中の boss または dungeon がある場合は名称、death、進行時間、参加者を追加する。参加者名は名前の途中で分割せず、1行20文字を目安に折り返し、2行目以降は参加者ラベル位置に揃えて表示する。15行の上限で全参加者を表示できない場合は、名前を無言で切り捨てず最終行へ `…ほかN人` を表示する。準備中は `P_6713`／`P_6714` の待機状態を追加し、Hub 未到着の参加者名を灰色にする。ただし、ハブ初期スポーンから待機離脱した `waitingAbsent` 本人には挑戦 Sidebar を返さない。デバフは `BUFF_SIDEBAR_DISPLAY` の設定にかかわらず優先表示し、通常バフは設定が有効な場合だけ表示する。同じ区分では獲得順を保ち、1ページ最大5件を buff / debuff の色分け、残り時間付きで追加する。表示対象の総件数と現在ページ／総ページ数を効果欄の見出しへ表示する。ページサイズは現在の空き行数に合わせ、最後まで閲覧できる。折り返しで増える参加者行も含めて全体は15行以内とし、設定有効時のMSPT / PINGの2行と、参加者・ボス／ダンジョン名の必須行を先に確保する。残り1行では件数・ページ案内を効果行へ併記し、利用可能行数まで挑戦任意情報とバフ件数を縮小する。非 gameplay mode では同 objective だけを unregister する。

各10tick更新では現在のobjectiveのscoreを読み、不要になった行だけをresetし、新規行・scoreが変わった行だけを設定する。文字列とscoreが同じ行は書き直さない。scoreboard/objectiveの置換や外部からのscore変更も現在値から判定するため、永続的なプレイヤー別キャッシュは持たない。重複文字列は従来どおり後方の行のscoreを採用する。

## 5. tab list 描画

クラス名: `PlayerHudView`
物理名: `renderTabList`

header に `ASTRAL RECORD` を表示し、performance 表示が有効な場合だけ MSPT と ping を色分けして追加する。network 機能が有効なRPGでは、Tabヘッダー/フッターはProxyが正本となるため `PlayerHudService` からこの描画を呼び出さない。

各プレイヤーのリスト名は、ログインデータ反映時・アカウントモード変更時・クラス変更時・クラスレベル変更時・クラスマスター再読込時・AFK状態遷移時に `PlayerClassService.updatePlayerListName` が更新する。`AccountMode.ADMIN` はクラス・レベル・AFK接頭辞を付けず、`accountName#slotIndex` だけを表示する。それ以外はクラス短縮名 `shortName` と現在クラスレベルを `[短縮名 Lv.レベル]` のタグとしてアカウント名の左へ表示し、最大到達時は `Lv.` を維持して数値部分を赤太字の `max` に置き換えた `Lv.max` を表示する。`shortName` に定義した色・装飾コードを反映し、AFK中は、その左に赤い`[AFK]`接頭辞を付与する。network 機能が有効なRPGでは、ProxyがTabを正本として更新するため、RPG側のプレイヤーリスト名更新を停止する。

## 6. 個人情報HUDの描画・解除

クラス名: `PlayerProgressHudView`
物理名: `render`, `hide`, `clear`

プレイヤー本人の画面上部へ BossBar を2行表示する。プレイヤー行はレベル（転生中は転生前レベルを併記）と桁区切り付きの所持Goldを表示し、アカウントの経験値進捗をゲージに反映する。クラス行は現在のクラス名、クラスレベル（最大到達時は `Lv.max`）、レベル内EXPの割合とゲージを表示する。スキルツリーワールドではクラス行へ現在クラスの未使用CPと未使用PPを追加する。2行は周期更新で再利用し、解除時は該当プレイヤーの2行を非表示にする。
