# AstralRecord リソースパック

Java Edition と Bedrock Edition に戦闘 HUD、討伐リザルト、ダメージ数値、アクションリング、アイテムモデル、武器通常攻撃の演出、GUI共通アイコンを配布します。配布する装備モデルは `item-models.json`、GUI専用の46種・84割り当ては `gui-icons.json` で管理します。

## GUI

Java版では、互換版1の管理パックの適用成功後に、Plugin管理の1～6行チェストへ専用背景を表示します。暗いタイトル帯とクリーム色の文字を送信時だけ重ね、元のInventory・holder・内容・タイトル・操作を維持します。未適用・旧パック・固定URL・統合版では標準背景を使います。統合版でも、既存Geyserのカスタムアイテム登録を通して共通アイコンを利用できます。

メニューと Java 版プレイヤー設定は機能ごとの絵柄で区別します。ページ移動や決定などの共通操作には金縁を使い、ダミーと仕切りのアイコンは透過させて背景になじませます。通常のサバイバルインベントリには Java 版と統合版（Classic・Pocket）で専用背景を適用します。プレイ中の HUD ホットバーは青灰色で揃え、選択中は金縁を青緑の光が巡ります。実装済みGUI全体の棚卸しと、金床・ネイティブフォーム・HUDなどの対応境界は [GUI対応表](GUI_COVERAGE.md) に記載しています。

原画・CC0素材の出典とライセンスは [gui-source](gui-source/README.md) にあります。両配布パックへ `NOTICE-GUI.txt` を含めます。`python scripts/generate-gui-assets.py --check` と `python scripts/generate-gui-panels.py --check` で再生成結果を検証できます。Java 版の通常インベントリは `python scripts/generate-java-inventory.py --check` で検証できます。HUD ホットバーは `generate-java-hotbar.py` と `generate-bedrock-hotbar.py` の `--check` で検証できます。各スクリプトの `--preview <path>` は原画または配置図を生成します。

## 対象と構成

- Java Edition 1.21.11〜26.3（リソースパック形式 75.0〜97.1）は `astralrecord:damage_number` フォントを使います。縁付きの数字と記号を収録し、色と動きはサーバープラグインで設定します。
- Bedrock Edition は `astralrecord:damage_number` クライアントエンティティを使います。数字をカメラに向けて中央に揃え、上昇しながら縮小・フェードさせます。

### 戦闘 HUD と討伐リザルト

- 対応パックを適用したJava版では、戦闘アクションバーの左上に本人のレベル・所持Goldと、正式クラス名・レベル・EXPの2行を専用フォントで重ねます。統合版の個人情報はサイドバーに表示します。
- 両版の標準HUDも戦闘行に合わせて、HPはハート、吸収ハートのMPは雫、満腹度のENGは稲妻で表示します。MPの半分表示は吸収ハートの仕様に従い、残量ゼロでは雫が消えます。

- Java 版は管理配布パックの適用成功後に `astralrecord:combat_hud` を使います。通常アクションバーの HP・MP・ENG は専用アイコン、現在値と最大値、10段階ゲージで表示します。個人情報HUD対応パックでは専用の等幅数字を使い、ゲージを数値の下に並べます。配布マニフェストの `java.wideCombatMeter` が `1` のパックでは、24ピクセル幅のゲージ字形を使います。個人情報HUDの互換版3以降では幅5ピクセルの等幅数字と、アイコンや数値の間に広い余白を使います。戦闘の補足情報はリソースの下の行へ分けます。互換版4では正式クラス名と、戦闘行の右上に状態異常アイコンと残り秒数を表示します。正式名の字形はOFLのDotGothic16を用い、`scripts/generate-class-name-hud.py` で再生成できます。書体と字形は `scripts/generate-gameplay-hud.py` で再生成できます。HP が25%以下のときは通常の10 tick 更新でゲージが明滅します。討伐報酬は紋章が光る見出しと金の区切り線で表示します。
- 統合版は `ui/hud_screen.json` で通常のテキストアクションバーに金縁のプレートを描画し、HP・MP・ENG のラベルと数値を表示します。Geyser 拡張 1.6.0 以降は、必要な紋章アセットを登録した接続で、受取人専用のリザルト TextDisplay に `astralrecord:combat_result` モデルを割り当てます。既存の本文表示を使い、紋章はクライアントで弾んで光り、フェードします。
- パックを使えない接続は各版の通常テキストで読めます。更新は両パックを公開した後に RPG Plugin と Geyser 拡張へ反映し、クライアントを再接続します。

### 陽冠のサンバード

胸の太陽紋は台座・外周・光条・中心石を段差のある形状で組み、冠と顔の縁飾り、翼・背・尾羽には厚みと重なりを付けています。面の質感には明暗をまとめたピクセルの色を使います。両版の編集元は [blockbench/entities](blockbench/entities) にあり、128×128 px の素材テクスチャを埋め込んでいます。Java 版は `astralrecord:sunbird` を ItemDisplay の固定モデルとして使います。統合版は同識別子のクライアントエンティティに待機と羽ばたきのアニメーションを持ち、ルートボーンでパロットの表示倍率を補正します。制作と検証の基準は [モブモデル制作スキル](../.codex/skills/astralrecord-mob-model-author/SKILL.md) を参照してください。

Java 版の本体モデルは、被弾時に赤色のティントを適用します。通常時は白のティントで元の色を表示します。

Java 版は本体のアイテム定義から翼を上げる、下げる、引く、攻撃に踏み込む4つの姿勢を選びます。通常姿勢と各姿勢で同じテクスチャと被弾色を使います。統合版は待機、飛行、溜め、攻撃のアニメーションを切り替え、無敵中は動作とは独立して金色の光輪が回ります。

スキル演出の統合版エンティティは `astralrecord:sunbird_skill` です。`astralrecord:style` が形状を、`width`・`height`・
`length` が各軸の倍率を指定します。赤橙の危険範囲と青緑の安全円は、床の目印と高さのある外枠を組み合わせます。
外枠は予兆中の位置を保ちます。着弾時は火柱と衝撃波が中心から外へ広がり、ビームと突進では厚みのある火の波が
帯の長手方向（Z 軸）へ走ります。Java 版は各表示に4段階のモデルを用意し、統合版はクライアント側でボーンを動かします。

| スタイル値 | Java 版の `astralrecord:` に続くモデル名 | 形状 |
| --- | --- | --- |
| 0 | `sunbird_warning_circle` | 円の警告 |
| 1 | `sunbird_warning_line` | 直線の警告 |
| 2 | `sunbird_warning_boundary` | 隕石の影響範囲の外周 |
| 3 | `sunbird_impact` | 火柱 |
| 4 | `sunbird_warning_safe` | 安全圏 |
| 5 | `sunbird_impact_line` | 直線の火光 |

両版のパックを更新して配布します。統合版の姿勢・無敵状態の通知には Geyser Extension 1.15.0 を使います。対応アセットのある接続で専用モデルを表示し、旧パックの接続では利用できる従来表示を使います。

本体の姿勢は Blockbench Web 5.2.2 で確認しました。攻撃演出は両版の配布モデルと表示倍率を使い、水平・浅い俯角・真上からオフスクリーンで描画しました。予兆の境界と発動中の連続フレームを確認しています。Minecraft のゲーム画面での描画は未確認です。

### 月蝕の綴り魔女 セレネア

`selenea_eclipse_witch` は三日月の帽子、紫のローブ、開いた魔導書を持つモデルを使います。Java 版は ItemDisplay で待機・移動・詠唱・発動・復帰の姿勢を切り替え、被弾・無敵中は装飾の色を変えます。統合版は `astralrecord:selenea` で姿勢を補間し、無敵中は帽子の周りに光を回します。両版の編集元は [blockbench/entities](blockbench/entities) にあります。

墨の三叉、呪頁の落款、月輪、月蝕の通路と大術、封頁、通常波、三連弾に専用演出を重ねます。危険範囲は紫の立体枠、退避・解除の目印は青緑で表示し、通常波は床面を低く広がります。統合版の `astralrecord:selenea_skill` は `style` で形状を選び、`width`・`height`・`length` で各軸の寸法を受け取ります。技の配布アセットは `python scripts/generate-selenea-skill.py --check` で検証できます。

両版のパックに互換マーカー `selenea-presentation-version.txt` を収録します。Java 版は配布マニフェストの `java.seleneaPresentation` が文字列 `"1"` の管理パックの適用成功後に表示します。統合版は対応パックを配布した接続で Geyser 拡張 1.18.0 と AstralRecordProxy 1.6.0 を経由して表示します。非対応の接続ではウィッチと従来の予兆を使います。

本体は Blockbench Web、技は両版の配布データをオフスクリーンで描画し、姿勢、境界、発動時の動きを確認しました。Minecraft 実クライアントでの描画は未確認です。

### ダメージ数値

| 統合版のスタイル値 | ダメージ | 色と印 |
| --- | --- | --- |
| 0 | 通常 | 赤 |
| 1 | 会心 | 金と星 |
| 2 | シールド吸収 | 水色とひし形 |
| 3 | 燃焼 | 橙と炎 |
| 4 | 感電 | 薄紫と稲妻 |
| 5 | 毒 | 黄緑と泡 |
| 6 | その他の状態異常 | 藤桃色と紋章 |
| 7 | ペット攻撃 | ミント色と肉球 |
| 8 | ペットの会心 | ミント色と肉球・星 |

会心は大きく弾んで 1.8 秒表示します。ほかのダメージ数値は 1.6 秒表示し、状態異常は横移動を抑え、ペット攻撃は通常より少し弾ませます。統合版のシールド吸収は登場時に横へ広がります。

統合版は AstralRecordGeyserExtension `1.4.0` とパック `1.1.0` 以降で全9種類の専用モデルを使います。`astralrecord:length` は最大 17 文字、`astralrecord:size` は 0.5～2.0 です。`astralrecord:glyph_0` ～ `astralrecord:glyph_16` の値は 0～9 が数字、10 がカンマ、11 がマイナス、12 が星、13 が種類ごとの印、14 が空白です。[Geyser のカスタムエンティティ API](https://geysermc.org/wiki/geyser/custom-entities/)

形式の参照先は [Java 1.21.11](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11)、[Java 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3)、[Bedrock のレンダーコントローラー](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/render_controller.v1.8.0?view=minecraft-bedrock-stable)、[Bedrock のマテリアル](https://learn.microsoft.com/en-us/minecraft/creator/documents/material-files?view=minecraft-bedrock-stable) です。リポジトリ内の参照先は `resourcepack.config.json` にまとめています。

### アクションリング

Java 版の対応接続では8枠のリングを画面中央に固定します。視点を動かすと対象枠が光り、確定すると選んだアイコンが中央に移ります。クールダウンはゲージと注視時の残り秒数で示し、MP不足、ENG不足、発動制限は異なる記号で示します。Java 版は注視中と確定後のスキル名をリング中央の字幕に表示します。現行の統合版は、武器を持ってしゃがみながら使うとスキル名と残り CT を載せた標準フォームを開きます。しゃがみを解除して武器を使うと、選択したスキルを発動します。

Java 版は配布マニフェストの `java.actionRingPanel` が固定 HUD とスプライト台帳の互換性を示す場合に、専用フォントを Title に描画します。Java 版で対応を確認できない接続では従来のワールド表示を使います。統合版のリング用 JSON UI とモデルは旧 RPG 向けの互換アセットです。

### 武器通常攻撃

剣は銀白の払いと火花が照準前方を横切り、槌は打ち下ろした位置で衝撃波と破片を広げます。槍の刺突は発動地点から照準方向へ、通常攻撃の射程に沿って進みます。弓・短弓・長弓は矢の残光を共用し、ワンドとスタッフはそれぞれ別の魔力の残光と着弾の絵柄を使います。残光は飛翔経路に沿って2 tick間隔で表示し、敵への命中・地形との衝突・射程切れで終端の演出を再生します。発動・残光・着弾の各スプライトは32×32 pxの6コマです。

Java版は `astralrecord:weapon_attack` の字形を更新し、軌跡・着弾の対応は配布マニフェストで確認します。統合版は発動時に標準粒子を1つ表示し、Proxyから対応パックの通知を受けた接続にはGeyser拡張 `1.14.0` 以降を介して専用の発動・軌跡・着弾演出も送ります。軌跡・着弾の対応が確認できない閲覧者には従来の飛翔粒子と魔法命中粒子を送ります。更新後はAPIに新パックとマニフェストを配置し、RPGプラグイン・Proxy・Geyser拡張を更新して再接続してください。空振り・命中・壁・射程切れを一人称と他の閲覧者から確認し、粒子OFFでも弾体で飛翔を確認できます。

### プレイヤースキル演出

発動・軌跡・着弾・範囲の4段階に6コマずつ用意しています。弓技の矢じり、剣技の軌跡、聖騎士の盾、魔法陣、星や稲妻など、各スキルのモチーフと配色を [skill-effects.json](skill-effects.json) に定義します。位置・再生時刻・判定範囲はプラグイン側の実行結果を使います。画面に向くスプライトと、ワールド上の範囲や経路を示す粒子を組み合わせます。

台帳の行番号を0始まりの固定スロットとします。新規スキルは末尾に追加します。段階番号は発動0、軌跡1、着弾2、範囲3です。両版のアトラスは行優先で、セル番号は `(スロット × 4 + 段階番号) × 6 + コマ番号` です。

- Java版は `astralrecord:skill_effect` のビットマップフォントを使います。字形は `U+E400` からセル番号順に並べます。
- 統合版は `astralrecord:skill_effect` を使います。`astralrecord:style` はスロット×4＋段階番号、`astralrecord:size` は表示直径を1/16ブロック単位で示します。レンダーコントローラーが段階ごとの形状を選び、固定 UV を持つ6面から再生中の1面を組み込みマテリアルで描画します。
- 配布物には `skill-effects-version.txt` を含めます。互換版は Java 版が `1`、統合版が `2` です。Java 版では配布マニフェストの `java.skillEffects` とパックの適用結果を確認します。統合版では Geyser 拡張 1.18.1 と Velocity の AstralRecordProxy 1.6.2 以降が接続単位で対応を通知します。API の配布パックとマニフェストを更新し、プラグインを更新してから再接続してください。

`python scripts/generate-skill-effect-assets.py --check --preview <PNGパス>` で、配布素材の再現性と全スキルの代表コマを確認できます。`--preview-skill <ID>` を追加すると、指定スキルの4段階を各6コマで確認できます。

### 効果音

ソード、ハンマー、スピア、ボウ、ショートボウ、ロングボウ、ワンド、スタッフの通常攻撃音は、武器ごとに3種類のテイクから再生します。GUIは選択、ダミー枠、アクションリングの枠移動、開閉、ページ切り替え、トグル、確認、拒否、成功、購入成立の音を使い分けます。

音源は新規に合成したモノラル Ogg Vorbis で、`assets/astralrecord/sounds/` に保存します。`assets/astralrecord/sounds.json` に登録した音だけをパックに収録します。ビルド時に同じ音源から統合版の `sounds/sound_definitions.json` を生成し、両版で `astralrecord:` から始まる同じイベント ID を使います。[Bedrock のカスタムサウンド](https://learn.microsoft.com/ja-jp/minecraft/creator/documents/addcustomsounds?view=minecraft-bedrock-stable)

Java版は効果音対応の管理パックを適用した接続で専用音を再生します。統合版は更新した AstralRecordProxy と Geyser 拡張で接続時の対応パックを確認します。RPG の `network.enabled: true` と Geyser の `gameplay.force-resource-packs: true` を設定し、更新したパックを配布した後にクライアントを再接続します。対応を確認できない接続では標準音を再生します。

## アイテムモデル

Filebase の `icon` が基本素材、`customModelData` がモデル番号です。Paper はクライアントへの送信アイテムに番号を適用します。ビルドは `item-models.json` をもとに、JE 用の振り分けと、Geyser が BE 用パックから読み込む登録情報を生成します。パックとマスターの番号が一致すると、JE は該当の立体モデル、BE は対応する手持ちモデルとインベントリアイコンを使います。未登録の番号は各版の基本素材で表示します。

1. 台帳の `id` に小文字のアセット名、`base_item` にマスター `icon` の Minecraft ID、`custom_model_data` に同じモデル番号を設定します。モデル番号は 1～16777215 の整数で、アイテム間で重複させません。`java_virtual_trident` は両版の長押し表示に参加する武器なら `true`、通常表示のみなら `false` を明記します。
2. Java Block/Item の書き出し先は `assets/astralrecord/models/item/custom/<id>.json`、テクスチャは `assets/astralrecord/textures/item/custom/<id>.png` です。新しい基本素材を使う場合は `java-item-fallbacks/<base_itemのパス>.json` に対象クライアントのバニラ定義を置きます。弓は `<id>_pull_0.json` ～ `<id>_pull_2.json` も用意します。
3. BE では `bedrock/models/entity/items/<id>.geo.json`、`bedrock/textures/entity/items/<id>.png`、`bedrock/textures/items/<id>.png`、`bedrock/attachables/<id>.json`、`bedrock/animations/<id>.animation.json` を使います。attachable の識別子は `astralrecord:<id>` です。パックは `astralrecord.<id>` のアイコンキーを生成します。
4. Blockbench の編集元は `blockbench/items/` に保存します。変更後は両版の書き出しと BE のインベントリアイコンも更新します。制作手順は [アイテムモデル制作スキル](../.codex/skills/astralrecord-item-model-author/SKILL.md) を参照してください。

JE で素材の振り分けだけを確認する場合は `/give @s minecraft:wooden_sword[minecraft:custom_model_data={floats:[21001.0f]}]` を使えます。通常攻撃と既存アイテムの表示更新は、ゲーム内でマスター由来のアイテムを使って確認します。

ノクスソードは 21001、ノクスボウは 21002、ノクススタッフは 21003 を使います。通常攻撃の腕振りと戦闘エフェクトはサーバー処理に同期します。BE の手持ちモデルは腕振りの進捗に合わせて動きます。バニラの弓の使用状態では、JE は3段階の引き絵、BE は弦の変形を使います。

### アイテムのツールチップ

両版で濃紺の背景と金の枠を使い、対応するパックを適用した接続では、AstralRecord のアイテム名の先頭に種類を表す絵柄を表示します。絵柄は輪郭と明暗を描き分けた16×16 pxの原画で、剣・槌・槍・弓・ワンド・スタッフや、瓶入りのポーションなど18種類です。弓の派生型は同じ絵柄を使います。

Java 版は `astralrecord:shared_item` で専用の背景と枠を選び、`astralrecord:tooltip_icons` で絵柄を描画します。統合版は `textures/ui/purpleBorder.png` を差し替えるため、この画像を参照する標準ツールチップや一部のチャット・ポーズ画面のUIにも同じ枠が適用されます。統合版の絵柄は `font/glyph_E6.png` に収録しています。両版のパックは機能の互換判定に使う `item-tooltips-version.txt` を含みます。[Minecraft Java Edition 1.21.2](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-2) · [Mojang の統合版ツールチップ画像の伸縮設定](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/textures/ui/purpleBorder.json)

原画の編集先は `assets/astralrecord/textures/font/tooltip_icons.png` です。更新時は左上の256×32 pxを統合版の256×256 pxのフォント画像に同じ位置でコピーします。既存の `U+E600` ～ `U+E611` の割り当てを維持し、両版で同じ絵柄を使います。配布用 PNG を最近傍拡大し、背景と枠を9分割で伸縮した静止画で、全18種類の輪郭とポーション名の配置を確認しました。実クライアントの描画は未確認です。

### 武器の握り位置

`java_virtual_trident: true` の武器は、台帳の `grip` に Java モデル座標の握りの中心を `[x, y, z]` で登録します。モデルは握りから先端が +Y 方向になるように作ります。ビルドは握りが形状の内側にあること、一人称・三人称それぞれの左右の手で基本素材の基準に収まることを確認します。位置はモデルの 0.5 px 以内、向きは基準ベクトルとの内積 0.95 以上です。弓では引き段階にも同じ検証を行います。新しい基本素材の武器には `item-model-grip-targets.json` に4つの表示姿勢の基準を登録します。

BE の対応するモデルは `grip` ルートを `[0, 0, 0]` に置き、`q.item_slot_to_bone_name(context.item_slot)` で手にバインドします。握りのキューブはこの原点を含め、待機アニメーションで `grip` を `[0, 24, 0]` 付近に置きます。三人称ではバニラの手の前端まで、Z を最大 3 px 前に出せます。`first_person` と `third_person` はそれぞれ `context.is_first_person` と `!context.is_first_person` で1回ずつ有効にし、待機中はループさせます。縮尺を指定する場合は全軸を 0.5～2.0 に収めます。攻撃や弓を引く動作は待機姿勢の `grip` を維持します。[Mojang のヒューマノイドモデル](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/entity/humanoid.custom.geo.json)

ビルドはモデルの +Y 軸を基準に姿勢を検証します。一人称の主手では通常の腕の回転を合成し、上向き成分を 0.8 以上、奥行き成分の絶対値を 0.5 以下にします。三人称では手のローカル座標で前向きの成分を 0.8 以上にします。形状の先端と刃や弓の面は Blockbench でも確認します。[Mojang の一人称アニメーション](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/player_firstperson.animation.json)

副手用の補助装備4点は、一人称の手持ち縮尺を 0.62、三人称を 0.86 にしています。Java 版は左右の握りと装飾面の向きを確認し、盾を使用すると手持ち姿勢だけを変える子モデルに切り替えます。統合版は一人称で主手・副手を分け、盾の一人称防御姿勢も切り替えます。盾の姿勢はバニラの握り位置を専用モデルの原点に換算したものです。統合版の三人称ではプレイヤーの手にバインドします。防御時に腕を上げる動作は Geyser 経由の実クライアントで未確認です。 [Mojang の盾アニメーション](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/shield.animation.json) · [Mojang のプレイヤー制御](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animation_controllers/player.animation_controllers.json)

アクションリングの長押し設定が有効で候補が2つ以上の場合、本人の選択中の武器を仮想トライデントとして送ります。JE は `java_virtual_trident` を指定した武器の待機モデルを表示できます。パック形式 83 以降もオーバーレイで通常のトライデント表示を維持します。

BE では仮想トライデントで長押し入力を受け取りながら、主手の表示を `astralrecord:held_weapon_model` のモデル番号で切り替えます。番号が 0、対象武器の登録外、または副手の場合は、バニラのトライデントのモデルと使用アニメーションを使います。ビルドは `java_virtual_trident: true` の武器だけで切り替えを生成し、Geyser 用カタログに各アイテムの `held_weapon` を出力します。各武器の attachable の `first_person`、`third_person`、`attack` とその条件を再利用します。これらが使う変数は `scripts.pre_animation` で、配列の各要素につき1回の変数代入で設定します。弓の引き絵は実際に弓を使用しているときに適用されます。 [Mojang のトライデント定義](https://github.com/Mojang/bedrock-samples/blob/a3b394c507a6b11a3c6f61552e778ff5c4b89fd2/resource_pack/attachables/trident.entity.json)

新しい BE 登録はパックを API に配置した後の Proxy 再起動で有効になります。

### 2Dアクセサリー

アクセサリーのピクセルアイコンを配布するときは、台帳に `render_mode: "generated"` を指定します。省略または `"3d"` は従来の立体モデルです。`generated` は `java_virtual_trident: true` や `grip` と併用できません。

入力は `assets/astralrecord/textures/item/custom/<id>.png` と `bedrock/textures/items/<id>.png` の2枚です。ビルドはJEの `minecraft:item/generated` を親にしたモデルを生成し、BEではアイコンとアトラスを登録します。Blockbench編集元や手持ちattachable、geometry、animationは要求しません。カタログの `render_mode` を読む対応Geyser拡張が必要です。アクセサリースロットの装備は性能に反映され、体にモデルを取り付ける処理は追加しません。

原画は [blockbench/pixel-art](blockbench/pixel-art/README.md) のパレット付きJSONで編集できます。`python scripts/generate-pixel-item-assets.py --check` で、全編集元と両版PNGのバイト一致を検証できます。

未登録の同素材を維持するため、基本素材ごとのバニラ定義を `java-item-fallbacks/` に用意します。プレイヤーヘッドは形式83以降の座標変換が異なるため、`transformed_item_models/player_head.json` も使います。時計の時刻表示、プレイヤーヘッドのスキン、壁などのブロック形状を含め、fallback全体を保持します。

## ウェイストーンと施設NPC

ウェイストーンは石碑の結晶と紋章を解除済みの青緑色と未解除の赤橙色で切り替えます。ストレージは金具を付けた木箱、溶鉱炉は鋳鉄の外枠と火格子のあるモデルです。既存の配置に適用します。

Java 版は表示用 ItemDisplay の `custom_model_data.strings[0]` で `assets/astralrecord/models/world` のモデルを選びます。ビルドで `LODESTONE`、`CHEST`、`BLAST_FURNACE` のアイテム定義へ選択条件を追加し、通常のアイテムと既存の GUI モデルをフォールバックに使います。

統合版は `astralrecord:world_display` の状態値に応じて、対応する形状とテクスチャを選びます。新しいパックの公開後に対応する AstralRecordGeyserExtension を配置して Proxy を再起動し、統合版の接続をやり直します。変換の仕様は [Geyser 拡張の導入手順](../10_plugin/AstralRecordGeyserExtension/README.md) を参照してください。

## ビルド

### 着用防具

防具のインベントリ・手持ちは `item-models.json` の3Dアイテムを使い、着用外観は追加台帳 `armor-assets.json` で管理します。各行は同じ `id` / `base_item` / `custom_model_data` と、身体部位 `slot`（`HEAD` / `CHEST` / `LEGS` / `FEET`）、`equipment_asset` を持ちます。マスターの `appearance.equipmentAsset` と同じ `astralrecord:<path>` を指定します。

- JE: `assets/astralrecord/equipment/<path>.json` の `layers.humanoid`（脚だけは `humanoid_leggings`）から、`textures/entity/equipment/<layer>/<texture>.png` を参照します。標準の立体鎧へ固有の層を貼る方式で、自由形状の全身itemモデルへ置き換える方式ではありません。
- BE: `models/entity/armor/<id>.geo.json` と `textures/models/armor/<id>.png` を追加します。既存attachableの `geometry.worn` と `textures.armor` がこれらを参照します。着用モデルは標準防具と同じ `waist` → `body` → 頭・腕・脚の骨名と親子関係でプレイヤーの動きに追従します。この骨には明示的な `binding` を付けません。梱包時に部位別の標準オフセットアニメーションを着用側へ追加します。 [Mojang のプレイヤー防具](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/entity/player_armor.json) · [部位別オフセット](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/armor.animation.json)
- 同attachableのrender controllerは、`controller.render.item_default` に `context.item_slot == 'main_hand' || context.item_slot == 'off_hand'`、`controller.render.astralrecord.armor_worn` に `context.item_slot != 'main_hand' && context.item_slot != 'off_hand'` の条件を付けます。手持ちと着用を同時描画しません。

`scripts/armor-assets.ps1` が台帳と登録済みitemの一致、標準防具素材とslot、JEレイヤー参照、BE着用geometry・texture・骨の階層・描画条件を検証して梱包します。防具のPNGは標準UVと同じ2:1（64×32、128×64、256×128）を使います。パックの検査は編集データの整合確認であり、両版の実クライアントでの着用確認を代替しません。

配布用itemカタログでは明示登録した身体防具だけ `armor: true` を付けます。Geyserはその防具に通常表示と非着用表示の2定義を用意し、Bedrock向け送信コピーの `custom_model_data.flags[0]`（trueが非表示）で選びます。このflagは標準4部位の防具でモデルIDがある場合だけ予約し、閲覧者の設定に従ってtrue/falseを毎回明示します。モデルID・追加float・他flag・string・colorは維持します。非着用表示の識別子 `hidden_armor_<id>` は生成用に予約し、同じアイコン `astralrecord.<id>` と手持ちアセットを使います。helperが身体描画を持たない派生attachableを生成するため、防具表示OFFでもGUI・手持ちは維持されます。通常の3D武器・2Dアイテム・馬鎧素材の武器はこの派生登録を行いません。台帳へのgenerated項目と仮想トライデント項目の登録も拒否します。 明示した防具にもJavaの4hand pose・握り点とBedrockのgrip原点・保持姿勢を同じ検査で要求します。conditional rendererの例外は台帳で照合できた防具の厳密な2条件だけで、その他の3D itemは標準controller一つを維持します。

Geyserは基本素材の `EQUIPPABLE.slot` から `minecraft:wearable` の身体部位を生成します。creative categoryはレシピ帳等の分類だけで、装備部位を指定しません。新しい防具登録は通常のitemと同様にProxy再起動・BE再接続で反映します。[Geyser custom items](https://geysermc.org/wiki/geyser/custom-items/)、[Minecraft JE 1.21.4](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-4)、[Bedrock custom armor](https://learn.microsoft.com/en-us/minecraft/creator/documents/addcustomitems?view=minecraft-bedrock-stable)

このディレクトリで以下を実行します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-resourcepack.ps1
```

`dist/AstralRecordResourcePack.zip`（Java）と `dist/AstralRecordResourcePack.mcpack`（Bedrock）を生成します。出力先は `-OutputDirectory <dir>` で指定できます。同じ入力ファイルからは同じバイト列を生成し、Bedrock の配布内容が変わるとパック UUID も変わります。生成したアーカイブはコミットしません。

字形の修正時は `scripts/generate-damage-number-assets.py` を Python で実行し、両版の PNG と関連 JSON を再生成します。通常のパックビルドに Python は不要です。

固定 HUD の原画と Java 版の字形は `scripts/generate-action-ring-hud.py` で再生成します。編集と描画確認には Pillow を使います。 `python scripts/generate-action-ring-hud.py --check --preview <PNGパス>` で生成済みアセットとプレビューを確認できます。従来のワールド表示の輪郭は `scripts/generate-action-ring-assets.py` で再生成します。

スキルアイコンは `scripts/generate-action-ring-icons.py` で生成します。生成元の既存番号を保持し、新しいスキルは未使用の番号に追加します。アイコンを更新したら、同じスプライトを使う固定 HUD 字形も再生成します。パックのビルド時に字形とスプライトの一致を検査します。Java・統合版のスキル表示用アイテムモデルの素材は `PAPER`、モデル番号は `920000 + スプライト番号` です。

通常攻撃の絵柄を変更するときは `scripts/generate-weapon-attack-assets.py` で両版の32 pxスプライトを再生成します。

## 全装備アートの編集元と確認範囲

通常実装の武器41点、防具82点、アクセサリ12点、オフハンドに実表示する補助装備4点を登録しています。既存のノクス武器3点を保持し、新規124点は3D、装着表示のないアクセサリ12点は2Dです。採集道具、スキル書、デバッグ専用マスターはこの装備アート台帳に含めません。

Minecraftの角形と粗い画素の材質分けを基準に、ノクスは簡素な初期装備、上位は素材の分離、縁、留め具、各系統の意匠を増やしています。防具のインベントリ・手持ち用の立体と、着用用の標準リグ・64×32原画は別の編集データです。JE着用時の自由形状の角・帽頂などは追加していません。

- `blockbench/items/`: native Blockbench 5.2.2で書き出した両版の編集元。防具は `<id>_worn.bedrock.bbmodel`、弓は3段階のJava draw原稿も保持します。
- `blockbench/equipment-drafts/`: 新規3D124点の個別cuboid・素材swatch設計原稿。
- `blockbench/armor-pixels/`: 着用原画のpalette・pixel-grid記録。原画PNGはJE/BE着用テクスチャと着用Blockbench原稿にも保存しています。
- `blockbench/pixel-art/`: 2Dアクセサリ12点の再生成可能な原画。
- `blockbench/equipment-source-manifest.json`: 全139点のマスター対応と編集元・配布元アセットのSHA-256。

編集元台帳のSHAは、`.json` と `.bbmodel` のCRLFをLFへ正規化して計算します。その他の内容は変換せず、PNG等のバイナリは元のバイト列を使います。Gitのcheckout改行設定が異なっても、同じ編集内容を同じハッシュで追跡できます。

制作は原則3〜4点ずつ分担し、native前後・斜め、実32pxアイコン、持ち点、全周UV、着用人形の顔開口を確認しています。弓はJava draw3段階とBE draw4状態・attack5状態を確認しました。パック参照、PowerShell間の決定性、独立レビューと実Minecraftクライアント検証は別の確認です。実クライアントの一・三人称、着用中の動作、装備切替、表示設定の切替は未確認です。

## サーバー配布

1. 初回は [Geyser Extension の導入手順](../10_plugin/AstralRecordGeyserExtension/README.md) に従い、Velocity の拡張を更新します。起動時と以後60秒ごとにAPIのBedrockパックを確認し、新しい接続へ配布します。
2. API・Web・Plugin を有効にした設定で [01-deploy-debug.bat](../60_tool/deploy-debug/README.md) を実行します。API の配置物に両パックと最新ハッシュを含めます。
3. Java 版はゲームサーバーから公開WebのダウンロードURLを受け取ります。配布先を変える場合は RPG Plugin の `resourcePack.managed.publicBaseUrl` を設定します。統合版は Geyser の接続時にパックを受け取ります。更新したパックの適用は再接続時です。

アクションリングやスキル表示の更新時は、対応する両版パックを API へ公開してから RPG Plugin を更新します。統合版のアイテム登録に更新を反映するには Geyser プロキシを再起動し、クライアントを再接続します。旧パックでは新規のスキル表示用モデルが紙の見た目になります。

RPG Plugin の `resourcePack.bedrockNamePrefixes` は、Velocity の Floodgate の接続名接頭辞と一致させます。現行環境は `.` です。
