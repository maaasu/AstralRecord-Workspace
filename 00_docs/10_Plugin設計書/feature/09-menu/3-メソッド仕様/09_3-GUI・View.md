# 09_3-GUI・View

## 1. menu facade

クラス名: `MenuView`

主な物理名:

- `open`: メインメニュー。
- `openEquipmentGui`: 装備画面。
- `openBuff` / `renderBuff`: バフ画面。
- `openClass`: クラス画面。
- `openCurrency`: 通貨画面。
- `openGuide` / `openGuideDetail`: ガイド一覧・詳細。
- `openTrash` / `renderTrash` / `openTrashConfirm`: ゴミ箱。
- `openSell` / `renderSell` / `openSellConfirm`: sell feature が利用する共通 view。
- `openStorage` / `renderStorage`: storage feature が利用する共通 view。
- `renderCraftShortcuts` / `clearCraftShortcuts` / `removeCraftShortcutItems`: craft shortcut 表示管理。
- `getMenuScreen`、`getPageIndex`、`getContentId`: `MenuInventoryHolder` の画面状態取得。

各画面の slot 判定と描画は `view/screen/*ScreenView` へ委譲する。

## 2. メイン画面

クラス名: `MainMenuScreenView`
物理名: `render`

54 slot inventory に [[09_2-ユースケース]].メインメニューを開く の配置で icon を描画する。クエスト icon は slot `23` に配置する。プレイヤー依存 lore は `PlayerGuiRenderContext` だけを参照し、描画中に各 repository を再取得しない。

## 3. アイコン生成

クラス名: `MenuIconFactory`
物理名: `create`, `equipmentDetails`, `currencyDetails`, `returnToBaseDetails`, `openHint`, `executeHint`

`MenuIconDefinition` の material、色、説明へ描画コンテキスト由来の詳細と操作 hint を組み合わせる。

## 4. プレイヤー一覧・詳細

プロフィール詳細は一画面へ集約し、内部 world ID ではなく world 表示名、status は compact 表示値を使用する。

クラス名: `PlayerListGui`、`PlayerDetailGui`
物理名: `open`

online player をページ表示し、選択対象のプロフィール・ステータス・装備情報を詳細画面へ描画する。holder には対象 UUID、用途、ページを保持する。

詳細画面は slot `4` にプロフィール、`10` から `16` にリソース、基本能力値、攻撃、防御、属性、状態異常、ユーティリティの全 status category、`30` にクラス、`31` にスキル情報、`32` に有効バフ、`38` にトレード、`42` に party 招待を配置する。各 status category icon をクリックすると、そのカテゴリに属する status だけを1 status 1 itemで表示する詳細 GUIへ遷移する。詳細 item は `StatusSnapshot` のステータス名、説明、現在値、基礎値、合計補正を表示し、項目数に応じてページングする。属性・状態異常の status は種類ごとの表示色を付け、属性・状態異常カテゴリ内ではダメージ・付与確率などの増加系、耐性系、貫通系の順に表示する。

スキル情報 icon の lore は、現在選択中の `SkillBindPreset` から解決したアクティブバインドを表示し、仕切り線の後へパッシブバインドを表示する。クリック後は使用許可スキル一覧と習得済みスキル一覧を選択する画面を開く。使用許可一覧は `SkillPermissionService`、習得済み一覧は `LearnedSkillService` を正本とし、各スキルをマスター定義の icon と `SkillPresentationUtil` による名称・説明・効果 lore でページ表示する。

## 5. 画面固有の描画不変条件

- `MenuIconFactory` は共有 icon 定義から呼出ごとに独立した `ItemStack` を生成する。通貨詳細は最大 10 件とし、超過時は ellipsis を加える。
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

## 5.5. 装備加工画面

クラス名: `EquipmentProcessingMenuScreenView`
物理名: `render`

修理と強化を `MenuScreen.EQUIPMENT_PROCESSING` の 54 slot GUI に集約する。通常画面の画面名は常に現在の加工状態を含む `装備加工｜修理`、`装備加工｜強化`、または `装備加工｜状態変化` とし、カーソルを合わせなくても判別できるようにする。`MenuInventoryHolder.contentId` は初期表示の `repair` または `enhancement` とする。状態変化は強化タブに属する自動切替表示であり、新しいタブや content ID は追加しない。

| 用途 | GUI slot | 内容 |
|:--|---:|:--|
| 加工状態の常時表示 | 画面名 / `1..7` / `4` | 画面名へ現在の加工状態を含める。修理は緑色の帯と `ANVIL`、通常強化は紫色の帯と `ENCHANTING_TABLE`、状態変化は水色の帯と `END_CRYSTAL` を表示する |
| ガイド / 修理タブ / 強化タブ / 情報 | `10` / `12` / `14` / `16` | 通常時は `BOOK` / `ANVIL` / `ENCHANTING_TABLE` / 修理は `SPYGLASS`、強化は `NETHER_STAR`。状態変化時のガイドは `END_CRYSTAL` とし、タブは強化を選択したままにする。タブ名の先頭 `▶` とモード別の色で選択中モードを補助表示する |
| 対象装備 | `20` | 両モード共通で下段から一時退避してセットする。対象枠クリックで所持品へ返却する |
| 消費素材の先行表示 | `21..23` | 強化に使う実アイテムアイコンと必要数 / 所持数を最大3種類まで表示する。素材マスタを解決できない場合は ID でなく `未登録の素材` と表示し、強化を実行不可にする |
| 必要素材一覧 | `25` | 強化では `CHEST` を表示し、クリックすると全必要素材を実アイテムで確認する。修理では `GOLD_INGOT` で素材不要・ゴールドのみ消費することを示す |
| 実行 | `24` | 3段目・右から3枠目。必要ゴールド / 所持ゴールド、全必要素材、実行可否を一つの lore へ表示する |

必要素材一覧は同じ加工GUI内で `PagedGuiView` を使って表示し、`0..44` に45種類ずつ実アイテムを表示する。`45` は前ページ、`49` は通常の加工画面へ戻る、`53` は次ページとする。一覧の画面名にも現在の加工状態を含める。状態変化の一覧では `49` を水色の `END_CRYSTAL` にして、状態変化の素材表示中であることを示す。一覧中は下段所持品から新しい装備を選択できず、対象装備・モードを保持したまま戻る。実行の二段階確認待ちは一覧を開くと解除する。手動でGUIを閉じたときだけ既存の加工セッション終了処理により対象を返却する。

修理・強化とも、下段の装備をクリックすると対象枠へ一時退避し、下段の表示からは消える。修理は対象の耐久・必要ゴールドを確認して実行枠から最大耐久まで回復し、成功後も対象枠に保持する。強化は消費素材・次のステータス増加・耐久値増加・成功率・失敗時挙動を確認して同じ実行枠から実行する。選択装備がその段階の強化上限に到達し、次の状態変化を実行できる場合は、同じ強化タブ・対象枠・素材一覧・実行枠のまま状態変化へ自動で切り替える。状態変化では変化後の名称・強化上限・エンチャント枠を表示し、常に成功する。状態変化は次のランクだけを順番に実行し、二段階確認は要求しない。モードを切り替えてもセット中の装備は保持し、対象枠クリックまたは画面終了時に所持品へ返却する。失敗時に装備破壊となる通常強化定義は、実行ボタンを二度クリックして確認する。

## 6. クラフトショートカット描画

クラス名: `CraftShortcutView`
物理名: `renderCraftShortcuts`

プレイヤー情報 shortcut の status lore は、`StatusValue` の合計範囲が基礎範囲と異なる項目だけを `StatusType` のカタログ順に表示する。表示上限は8件とし、超過時は末尾へ `… ほかN件` を追加する。変更項目がない場合と status 未取得の場合は、それぞれ判別できる案内を表示する。
