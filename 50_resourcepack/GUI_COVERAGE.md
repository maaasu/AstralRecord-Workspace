# GUI リソースパックの対応範囲

## 実装方針

矢印・戻る・閉じる・確定・取消・数量増減は明るい金縁で共通操作と分かるようにする。メニューと設定項目は機能ごとに輪郭と色を変える。ダミーと仕切りのモデルを透過させ、背景の枠をそのまま見せる。無効・処理中・未設定と保存案内は暗い凹面と記号で区別する。

専用アイコンの正本は `gui-icons.json`。同じ内容を RPG Plugin の `src/main/resources/gui-icons.json` へ同梱し、`shared/gui/GuiResourcePackIcons.java` が一度だけ読み込む。ビルドは Java の素材別振り分け、Bedrock のカタログ・アイコンを生成する。素材は変更せず、GUI 用として新規生成したコントロールの `custom_model_data.floats[0]` だけを設定する。数量・名前・説明・PDC・プロファイル・他の component は保持する。

`GuiItems.create` による自動適用は、名前が空白で説明を持たない黒・灰色のガラス板だけ。通常の素材アイコン、マスターのアイコン、実所持品、プレイヤースキンは一律変換しない。ページ・スクロール・戻る・閉じる・処理中は共通の生成メソッドで役割を明示する。メニューは `MenuIconDefinition` ごとに役割を選び、`ACCOUNT_INFO` は対象プレイヤーのスキンを表示するため専用モデルを設定しない。

赤・黄緑のコンクリートは「取消・確定」と「減らす・増やす」の双方で使われる。素材だけでは役割を推測せず、`ConfirmDialogView`、`GoldAmountSettingGui`、`MarketGui` の該当位置で明示する。ビルダーの赤い羊毛は選択位置、ダンジョン地図の黄緑ガラス板はクリア済みの部屋なので、これらを確認ボタンへ変更しない。

Java のチェスト背景は `GuiResourcePackTitle` が送信するタイトルのフォント字形で重ねる。対象は Plugin 管理の1～6行チェストで、対応する管理パックの適用成功を確認できた接続に限定する。元の Inventory・holder・内容・スロット番号・クリックやドラッグの処理は維持する。パック未適用、旧パック、統合版、ANVIL、通常のプレイヤーインベントリでは元のタイトルを使う。通常のサバイバルインベントリの背景は、Java 版では専用テクスチャ、統合版では Classic・Pocket の通常画面に専用パネルを指定して描画する。

## インベントリ GUI の棚卸し

下表のソースはすべて `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/` からの相対パス。holder の `getInventory()` が返す API 用の空インベントリではなく、実際の表示入口を調べた。ページ数、所持品、習得スキル、部屋数などで生じる動的なページは同じ画面系列として扱う。

共通対応とは、上記の Java チェスト背景と、その画面が利用する共通フレーム・ナビゲーションアイコンを指す。個別スキルのカードはリングと共通の絵柄を使用する。装備、通貨、モブ、報酬などのカードは既存のマスター素材・モデル・プロファイルを使う。

| 画面系列 | 実装済み画面 | 実際の表示入口 | パックの適用箇所・留意点 |
|:--|:--|:--|:--|
| 共通メニュー | メイン、装備、バフ、クラス、通貨、ガイド一覧・詳細 | `feature/menu/view/MenuView.java` | 共通対応、メニュー定義別の専用アイコン。プロフィールはプレイヤースキンを維持 |
| ゴミ箱・売却・ストレージ | 一覧、ゴミ箱確認、売却確認、ストレージの各フィルター | `feature/menu/view/MenuView.java`、`feature/sell/view/SellScreenView.java`、`feature/storage/view/StorageScreenView.java` | 共通対応。実アイテムの表示と取り出し領域を維持 |
| プレイヤー一覧・情報 | パーティー招待対象、プレイヤー一覧、プロフィール、習得スキル・現在のバインド、ステータス詳細 | `feature/menu/player/PlayerListGui.java`、`feature/menu/player/PlayerDetailGui.java` | 共通対応。各プレイヤーの顔と装備のモデルを維持 |
| 通貨両替 | ゴールド両替所 | `feature/currency/view/CurrencyExchangeGuiView.java` | 共通対応。金額・通貨カードを維持 |
| アイテム管理 | 管理者アイテム一覧、フィルター候補 | `feature/item/gui/ItemAdminGuiView.java` | 管理者画面も共通対応。アイテム自体のモデルを維持 |
| キャストディスク | スキルキャストディスク設定 | `feature/item/castdisk/CastDiskGui.java` | 共通対応。設定済みスキルとホットバー上のディスクに共通スキルアイコンを適用 |
| 装備オーブ・ルーン | 対象装備一覧、所持オーブ一覧、状態変化確認、必要素材一覧、ルーン装着・脱着・脱着ルーン選択 | `feature/item/service/OrbService.java`、`feature/item/gui/OrbGuiHolder.java` | 共通対応。実行中のロック、消費、候補再解決、下段操作を維持 |
| スキルマネージャー | 一覧、詳細、合成、確認 | `feature/skill/gui/SkillBindGui.java` | 共通対応。各スキルカードと下段の設定済みバインドに共通スキルアイコンを適用 |
| スキル忘却 | 一覧、忘却確認 | `feature/skill/gui/SkillForgetGui.java` | 共通対応。一覧と忘却確認の対象に共通スキルアイコンを適用 |
| シジル | 対象スキル一覧、装着、脱着、脱着シジル選択 | `feature/skill/service/SkillSigilOrbService.java`、`feature/skill/gui/SkillSigilOrbGuiHolder.java` | 共通対応。シジル・スキルの表示内容を維持 |
| スキルツリーの補助画面 | CP 消費元クラス選択、ノード解除確認 | `feature/skilltree/event/SkillTreeEventHandler.java` | チェスト部分を共通対応。フィールド上のツリー本体とは別 |
| ショップ | 商品一覧、購入確認、両替確認 | `feature/shop/gui/ShopGui.java` | 共通対応。商品と通貨のカードを維持 |
| クエスト | 掲示板、受領中クエスト | `feature/quest/gui/QuestGui.java` | 共通対応。マスターの名称・クエストアイコンを維持 |
| マーケット | 読込中、公開出品、自分の出品、出品アイテム選択、出品設定、購入確認、取り下げ確認 | `feature/market/gui/MarketGui.java` | 共通対応。数量増減だけは明示した ± アイコン。出品物の個体モデル・数量を維持 |
| 送信・取引 | 相手へのアイテム送信と送金 | `feature/trade/gui/TradeGui.java`、`feature/trade/gui/TradeGuiLayout.java` | 共通対応。送信枠、相手の表示、確定処理を維持 |
| 金額入力 | 送金・価格設定などの共通ゴールド入力 | `shared/gui/gold/GoldAmountSettingGui.java` | 共通対応。± と確定を個別に設定。半分・2倍・桁変更は既存の意味を維持 |
| メール | メール一覧、各フィルターとページ | `feature/mail/gui/MailGuiView.java` | 共通対応。報酬のモデルと受取状態を維持 |
| ログイン報酬 | 月別ログイン報酬カレンダー | `feature/loginbonus/view/LoginBonusGui.java` | 共通対応。日付・受取状態とカレンダーの配置を維持 |
| 冒険記録 | モブ一覧、カテゴリ、アイテム検索、モブ詳細、レベル選択、モブステータス詳細 | `feature/adventurerecord/gui/AdventureRecordGui.java` | 共通対応。モブ、素材、ステータスの内容アイコンを維持 |
| パーティー | 状況・招待、掲示板、メンバー操作、募集設定 | `feature/party/gui/PartyGui.java`、`PartyBoardGui.java`、`PartyMemberActionGui.java`、`PartyRecruitmentSettingsGui.java` | 共通対応。プレイヤーの顔を維持 |
| 募集文入力 | 金床を使った募集内容の文字入力 | `feature/party/gui/PartyRecruitmentMessageAnvilGui.java` | 専用チェスト背景の対象外。金床のテキスト欄・入力・確定の標準操作を維持 |
| ペット | 飼育員、ペット復活 | `feature/pet/gui/PetGui.java` | 共通対応。ペットとオーブの内容表示を維持 |
| プレイヤー設定 | Java の設定一覧 | `feature/playersetting/gui/PlayerSettingGui.java` | Java の設定15項目と保存案内に専用アイコンを表示。統合版は後述のネイティブフォーム |
| 転生 | 転生、転生開始確認、転生終了確認 | `feature/rebirth/gui/RebirthGui.java` | 共通対応、共通確認ボタン |
| 転送装置 | 転送先一覧 | `feature/teleporter/gui/TeleporterGui.java` | 共通対応。マスター指定の転送先名・アイコンを維持 |
| ワールド転送 | 管理者ワールド転送、インスタンス受付転送、種別選択、オーバーワールド転送 | `feature/world/gui/AdminWorldTeleportGui.java`、`InstanceTeleportGui.java`、`OverworldTeleportGui.java` | 管理者画面を含めて共通対応。選択条件・権限は変更しない |
| ダンジョン | 図鑑一覧・詳細、中断・帰還、緊急転送、部屋地図、報酬 | `feature/dungeon/gui/DungeonArchiveGui.java`、`DungeonCancelGui.java`、`DungeonEmergencyTeleportGui.java`、`DungeonMapGui.java`、`DungeonRewardGui.java` | 共通対応。部屋状態の色、接続・方向、報酬の実モデルを維持 |
| ボス | ボス挑戦の中断・帰還 | `feature/boss/gui/BossChallengeCancelGui.java` | 共通対応。クリア状態に応じた操作を維持 |
| 共通挑戦待機 | 待機から離脱する確認 | `shared/challenge/ChallengeWaitingLeaveConfirmGui.java` | 共通対応、共通確認ボタン |
| 練習用カカシ | カカシ設定 | `feature/trainingdummy/gui/TrainingDummyGui.java` | 管理画面も共通対応。設定値と操作を維持 |
| ビルダー | ホーム、範囲、素材、編集、コピー・貼付、球・円柱、繰返し・移動、使い方、実行前確認 | `feature/builder/gui/BuilderGui.java` | 全9画面を共通対応。素材や選択位置のアイコンを確認ボタンへ一律変換しない |

## チェスト以外の UI と版ごとの差

| UI | 実装 | 対応と境界 |
|:--|:--|:--|
| 通常のプレイヤー所持品、ホットバー、クラフト枠のショートカット | `feature/inventory/service/InventoryService.java`、`NormalInventoryLayout.java`、`HotbarRenderer.java`、`feature/menu/view/CraftShortcutView.java` | 共通アイコン生成を使うショートカット・スクロール操作へ専用アイコンを適用。所持品のスロットやモデルを維持。Java の通常画面は `textures/gui/container/inventory.png`、統合版は Classic・Pocket の通常画面で専用の背景を使用。装備・クラフト・所持品のスロットと既存の操作を維持 |
| GUI の下段専用表示 | `shared/gui/playerinventory/PlayerInventoryOverlaySupport.java`、`shared/gui/hotbar/HotbarShortcutClickSupport.java` | 既存の holder 契約、表示復元、BAG スクロール・ホットバー操作を維持。専用背景のために実所持品をダミーへ置換しない |
| 統合版プレイヤー設定フォーム | `feature/playersetting/gui/PlayerSettingGui.java#createBedrockForm`、`shared/gui/GuiOpenSupport.java#showDialog` | Paper Dialog の真偽値と選択欄、保存・取消を使用。Geyser が解釈するネイティブフォームで、今回のチェスト背景字形は使わない。フォーム基盤そのものを変える Bedrock `ui/*.json` 上書きは追加しない |
| ワールド上のスキルツリー | `feature/skilltree/service/SkillTreeVisualizer.java`、`SkillTreePacketDisplay.java`、`SkillTreeService.java` | 単一スキルを付与するノードの ItemDisplay に共通スキルアイコンを適用。その他のノードにはノードのアイコンを使う。統合版の線には粒子代替がある |
| アクションリング | `feature/skill/service/SkillActionRingDisplay.java`、`SkillActionRingService.java`、`ActionRingFrame.java` | 専用輪郭、共通スキルスプライト、クールダウン、選択マーカーを配布。両版ともパネル非対応時は従来のアイテム表示を使う |
| 戦闘 HUD のアクションバーとリザルト | `feature/hud/view/PlayerHudView.java`、`feature/hud/service/PlayerHudService.java`、`shared/display/CombatHudGlyphs.java` | 既存の Java 専用 HP/MP/ENG 字形・ゲージとリザルト紋章を維持。対応パックの判定と、未適用時のテキストを維持。今回の UI アイコンによりバーを組み替えない |
| サイドバー | `feature/hud/view/PlayerHudView.java#renderSidebar` | 標準 Scoreboard。情報量・最大行数・バフページ・ボス/ダンジョン情報を維持。標準クライアントが配置する背景や全体レイアウトは今回変更しない |
| BossBar | `feature/hud/service/AdminMessageBossBarService.java`、`feature/skill/service/SkillCooldownBossBarService.java`、`feature/dungeon/service/DungeonRoomBossBarService.java` ほか各機能の BossBar | 告知、クールダウン、詠唱、釣り、帰還、アイテム使用、ボス・防衛・スキルツリー読込などの標準バーを維持。色・進捗・セグメントの意味を専用アイコンと混同しない。全バニラ BossBar テクスチャの一括上書きは行わない |
| ダメージ数値・武器通常攻撃 | `shared/display/DamageNumberPacketAdapter.java` ほか既存の描画処理 | 既存の両版の専用アセットを引き続き含める。インベントリ GUI ではないため表示仕様を変更しない |

Java の独自タイトルフォントは統合版のチェストへ同じ座標で表示できないため、統合版にはタイトル字形を送らない。統合版の独自アイコンは既存の Geyser カスタムアイテムカタログを使う。新規の登録反映には、パック配布を更新したうえで Proxy の再起動と再接続が必要になる。今回のローカル作業は Push・API 配置・本番更新を含まない。

`inventory` は通常所持品の情報ボタンとオーブ画面の所持品切替、`storage` はオーブ画面の倉庫切替に明示適用する。`profile` の原画・登録は予備であり、実際のプレイヤー情報には適用しない。顔写真の代わりに汎用の人型が表示される変更は含まない。

## 検証の区分

検証結果は、パックの JSON・参照・決定的ビルド、Plugin のコンパイルと既存検証、実サーバー上のパケット・アイテム確認、Java/統合版の実クライアント目視を区別して記録する。PNG のプレビューやパケット受信だけで、実画面の視認性と操作感まで検証済みとは扱わない。個別の実施結果は作業完了時の検証記録に従う。

チェスト背景と共通アイコンの初回導入時の確認結果

| 検証 | 結果 |
|:--|:--|
| 原画と背景 | 31絵柄の両版PNG、6行サイズの背景とフォントの再生成バイト一致。原寸16px・32px・拡大と配置図を確認 |
| パック | PowerShell 5.1と7および隔離した配布関数による再生成のSHA-256一致。1798 Java / 1165 Bedrockエントリ、全JSON、68割り当て、既存139モデル、出典NOTICEと参照を検査 |
| Plugin | `mvn -B test`で1048テスト、失敗・エラー・スキップ0。ログ/メッセージ367/1110 ID検証、1044テストメソッドの設計追跡検証が成功 |
| API | .NET 10ビルド成功。NuGet脆弱性情報の取得不可（NU1900）は環境の通信制限による警告。変更コードのエラーなし |
| 隔離した実Purpur/ProtocolLib | 実際の共通ヘルパーと外部RPG境界のスタブで416項目成功。68アイコンの353メタデータ検査、1～6行の背景、11画面、非適用時の標準表示、holder・内容・リスナー解放・キャンセル・実クリックを確認。深いタイトル要素も送信だけクリーム色、元のサーバー構造・色を保持 |
| 互換版判定 | 実Repository/Serviceソースの隔離検査で12入力・73項目成功。文字列 `"1"` と適用成功の両条件が必要。旧・未知・不正な値は無効 |
| 警告比較 | 同じMaven/JDKで作業前HEADを隔離コンパイルし、本体64警告の位置・種類が一致、新規0。既存Javadoc・serialVersionUIDと外部Kotlin/JDKの警告を分類 |
| 独立レビュー | 配布マーカーの改行とタイトルの低コントラストを修正。最新developへのリベース後はcheckout改行差による生成チェック・出典NOTICEの差も解消し、対象を再検証 |

実Java/統合版クライアントの描画、GUI倍率・ホバー・実ドラッグ操作、実パック取得から適用通知までのE2E、フルAstralRecord/API連携は未検証。実クライアント操作はGDLauncherへのアプリ操作承認がタイムアウトして進められなかった。実通信ドラッグはプロトコルボットで再現できず、ドラッグ確認は明示的な合成Bukkitイベントのみ。統合版のネイティブ設定フォームはPaper Dialogの通信が維持されることまでで、Geyser/Bedrockでの実描画確認は含めない。
