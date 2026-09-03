# 09_3-GUI・View

## 1. menu facade

クラス名: `MenuView`

主な物理名:

- `open`: メインメニュー。
- `openEquipmentGui`: 装備画面。
- `openBuff` / `renderBuff`: バフ画面。
- `openClass`: クラス画面。
- `openCurrency`: 通貨画面。
- `openGuide` / `openGuideDetail`: ガイド一覧・詳細。`openGuide(Player)` は `GuideConditionType.GUIDE_OPENED` を記録して title を解除し、ページ移動用 overload は開封イベントを再記録しない。
- `openTrash` / `renderTrash` / `openTrashConfirm`: ゴミ箱。
- `openSell` / `renderSell` / `openSellConfirm`: sell feature が利用する共通 view。
- `openStorage` / `renderStorage`: storage feature が利用する共通 view。
- `renderCraftShortcuts` / `clearCraftShortcuts` / `removeCraftShortcutItems`: craft shortcut 表示管理。
- `getMenuScreen`、`getPageIndex`、`getContentId`: `MenuInventoryHolder` の画面状態取得。

各画面の slot 判定と描画は `view/screen/*ScreenView` へ委譲する。

ガイド詳細の手順 item は、白色の `text`、灰色の `details[]`、マスターのカラーコードを反映した `action.description` を順に lore へ表示する。`action.description` にカラーコードがない場合は灰色とする。スキルマネージャーを開く操作は紫色で表示する。action が定義された手順をクリックした場合は `GuideActionService` へ委譲し、NPC の座標案内・発光、メール GUI 起動、またはスキルマネージャー GUI 起動を実行する。action がない手順や表示対象外スロットは操作不可とする。

## 2. メイン画面

クラス名: `MainMenuScreenView`
物理名: `render`

54 slot inventory に [[09_2-ユースケース]].メインメニューを開く の配置で icon を描画する。クエスト icon は slot `24` に配置する。`ACCOUNT_INFO` は描画対象 `Player` のスキンを `PLAYER_HEAD` へ設定する。プレイヤー依存 lore は `PlayerGuiRenderContext` だけを参照し、描画中に各 repository を再取得しない。

## 3. アイコン生成

クラス名: `MenuIconFactory`
物理名: `create`, `createPlayerInfo`, `equipmentDetails`, `currencyDetails`, `returnToBaseDetails`, `openHint`, `executeHint`

`MenuIconDefinition` の material、色、説明へ描画コンテキスト由来の詳細と操作 hint を組み合わせる。メインメニューの共通 icon 定義は用途ごとに異なる Material を使い、同じ意味に見える本・プレイヤーヘッドなどを重複させない。`createPlayerInfo` はメインメニューとステータス shortcut の両方で対象プレイヤーのスキンを設定する。

## 4. プレイヤー一覧・詳細

プロフィール詳細は一画面へ集約し、内部 world ID ではなく world 表示名、status は compact 表示値を使用する。

クラス名: `PlayerListGui`、`PlayerDetailGui`
物理名: `open`

online player をページ表示し、選択対象のプロフィール・ステータス・装備情報を詳細画面へ描画する。holder には対象 UUID、用途、ページを保持する。

詳細画面は slot `4` にプロフィール、slot `22` に装備、`10` から `16` にリソース、基本能力値、攻撃、防御、属性、状態異常、ユーティリティの全 status category、`30` にクラス、`31` にスキル情報、`32` に有効バフ、`38` にトレード、`42` に party 招待を配置する。各 status category の lore と詳細 GUIでは合計値が 0 の status を表示せず、カテゴリ内に表示可能な status がない場合は灰色で「ステータス情報はありません」と表示する。装備 icon は自分の詳細画面では編集可能な装備画面へ、他プレイヤーの詳細画面では対象装備を参照する専用画面へ遷移させる。参照専用画面では上段・下段のクリックによる装備脱着、所持品移動、装備状態保存を行わない。各 status category icon をクリックすると、そのカテゴリに属する status だけを1 status 1 itemで表示する詳細 GUIへ遷移する。詳細 item は `StatusSnapshot` のステータス名、説明、現在値、基礎値、合計補正を表示し、項目数に応じてページングする。属性・状態異常の status は種類ごとの表示色を付け、属性・状態異常カテゴリ内ではダメージ・付与確率などの増加系、耐性系、貫通系の順に表示する。

スキル情報 icon の lore は、現在選択中の `SkillBindPreset` から解決したアクティブバインドを表示し、仕切り線の後へパッシブバインドを表示する。クリック後は使用許可スキル一覧と習得済みスキル一覧を選択する画面を開く。使用許可一覧は `SkillPermissionService`、習得済み一覧は `LearnedSkillService` を正本とし、各スキルをマスター定義の icon と `SkillPresentationUtil` による名称・説明・効果 lore でページ表示する。

## 5. 画面固有の描画不変条件

- `MenuIconFactory` は共有 icon 定義から呼出ごとに独立した `ItemStack` を生成する。`CURRENCY` の `BUNDLE` アイコンはバニラの内容量を表示しない。通貨詳細は最大 10 件とし、超過時は ellipsis を加える。
- equipment 画面は main hand / off hand に専用の空 marker を使い、accessory GUI slot を `AccessorySlotType` へ対応付ける。空 slot の自動選択は同じ equipment tag category 内に限定する。

equipment 画面は 54 slot とし、次の配置と空 marker を正本とする。

| 用途 | GUI slot | 空 marker / 将来枠 |
|:--|:--|:--|
| player status / pet / back | `0` / `16` / `49` | `PLAYER_HEAD` / `SADDLE` / `SPECTRAL_ARROW` |
| main hand / off hand | `19` / `21` | `ITEM_FRAME` / `GLOW_ITEM_FRAME` |
| head / chest / legs / feet | `11` / `20` / `29` / `38` | 対応する leather armor。空 marker の RGB は `(48,48,48)` |
| memory 1 / 2 | `27` / `36` | `HOPPER` |
| amulet | `23` | `CHEST_MINECART` |
| talisman 1 / 2 | `31` / `33` | `FURNACE_MINECART` |
| core | `32` | `HOPPER_MINECART` |
| relic 1 / 2 | `39` / `43` | `TNT_MINECART` |
| charm 1 / 2 / 3 | `40` / `41` / `42` | `MINECART` |
| gauge large / medium / small | `26` / `35` / `44` | `SPAWNER` |

accessory slot の逆引きは `23=AMULET`、`31=TALISMAN_1`、`33=TALISMAN_2`、`32=CORE`、`39=RELIC_1`、`43=RELIC_2`、`40..42=CHARM_1..3` とする。同一 category に複数 slot がある場合は番号の小さい空 slot から選び、同一 category に空きがなければ `-1` とする。

## 5.5. オーブ操作画面

クラス名: `OrbGuiHolder` / `OrbService`

旧装備加工タブ画面は削除する。通常プレイヤーインベントリのオーブをクリックすると、装備操作では現在実行可能な装備、シジル操作では現在実行可能な習得済みスキルだけを同じ候補一覧位置に表示する。

- 装備中の候補を先頭にし、その後へ BAG / HOTBAR の候補を並べる。同一装備 instance は一度だけ表示する。
- 強化候補の lore には装備ごとの成功率と `変化なし` / `指定強化値になる` / `現在値-1` の失敗挙動を表示する。オーブ lore へ装備ステータスを動的追記しない。
- 状態変化候補には数値 rank ではなく、即時次状態の名称を「<状態変化名>へ変化」と表示する。選択後は専用確認画面へ移り、必要素材・通貨、所持数、不足を確認してから実行する。
- 候補クリック後のAPI操作・正本照合中は対象slotへ `CLOCK` を一度だけ表示し、候補、下段、ホットバー、ページ、戻るを含むすべてのクリックを無効化する。固定フレーム演出・反復サウンド・確定後の追加待機は行わない。
- `OrbGuiHolder` は `HotbarShortcutGuiHolder` を実装する。通常時の上段クリックはオーブ専用処理へ委譲し、下段プレイヤーインベントリのクリックは `HotbarShortcutClickSupport` へ委譲するため、BAG の slot `17` / `35` の上下スクロールとホットバー割当を利用できる。ドラッグはインベントリ全体をキャンセルする。
- 正本照合完了と同じtickで候補全体を再計算して結果音を鳴らす。オーブが0個なら閉じ、残っていれば一覧を維持する。状態変化は完了時に閉じる。
- 表示 ItemStack は操作権限の根拠にせず、実行直前に inventory entry ID、equipment instance ID、所有 account、効果条件を再解決する。

## 5.4. インベントリ内オーブ一覧画面

クラス名: `OrbGuiHolder` / `OrbService`

`INVENTORY_ORB_LIST` は54スロットのページング GUI とし、ショップと同じく最外周をダミーアイテムで囲み、内側の28スロットへ所持中のオーブ種類を表示する。BAG / HOTBAR の同一 item ID は集約して1項目にし、ItemStack の表示個数を超える合計数量は lore の `所持数` へ表示する。

- 前ページ、ページ情報、次ページは下段の `45`、`49`、`53` に配置する。
- オーブ項目をクリックすると、正本 state から同じ item ID の有効 entry を再解決し、通常の `LIST` 画面をそのオーブの装備候補一覧として開く。
- `LIST` の候補が空の場合はオーブを消費せず `INVENTORY_ORB_LIST` へ戻し、一覧から別のオーブを続けて選択できる。
- 通常のオーブ装備操作 GUI の下段中央 `49` にある起点オーブをクリックした場合は、現在の操作が非ロック状態であることを確認して `INVENTORY_ORB_LIST` へ切り替える。
- `INVENTORY_ORB_LIST` も `HotbarShortcutGuiHolder` の共通契約に従い、上段の一覧操作と下段プレイヤーインベントリのスクロール／ホットバー操作を分離する。
- 一覧の枠・ページ情報・無効なページボタンは操作不可とし、一覧表示中の ItemStack 自体を権限判定へ使用しない。

## 6. クラフトショートカット描画

クラス名: `CraftShortcutView`
物理名: `renderCraftShortcuts`

プレイヤー情報 shortcut の status lore は、`StatusValue` の合計範囲が基礎範囲と異なる項目だけを `StatusType` のカタログ順に表示する。表示上限は8件とし、超過時は末尾へ `… ほかN件` を追加する。変更項目がない場合と status 未取得の場合は、それぞれ判別できる案内を表示する。
`AstPlayer.isBedrock=true` の残存 shortcut 消去は、クラフト枠・所持品・カーソルのいずれかに実際の shortcut item が存在する場合だけ対象を変更する。ログイン・ワールド変更などの一連の cleanup では、変更があった場合だけ `updateInventory()` を最大1回実行し、すでに除去済みなら inventory 全体同期を発生させない。
