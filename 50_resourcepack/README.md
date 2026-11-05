# AstralRecord リソースパック

Java Edition と Bedrock Edition に戦闘 HUD、討伐リザルト、ダメージ数値、アクションリング、アイテムモデル、武器通常攻撃の演出を配布します。配布するアイテムモデルは `item-models.json` で管理します。

## 対象と構成

- Java Edition 1.21.11〜26.3（リソースパック形式 75.0〜97.1）は `astralrecord:damage_number` フォントを使います。縁付きの数字と記号を収録し、色と動きはサーバープラグインで設定します。
- Bedrock Edition は `astralrecord:damage_number` クライアントエンティティを使います。数字をカメラに向けて中央に揃え、上昇しながら縮小・フェードさせます。

### 戦闘 HUD と討伐リザルト

- Java 版は管理配布パックの適用成功後に `astralrecord:combat_hud` を使います。通常アクションバーの HP・MP・ENG は専用アイコン、現在値と最大値、10段階ゲージで表示します。HP が25%以下のときは通常の10 tick 更新でゲージが明滅します。討伐報酬は紋章が光る見出しと金の区切り線で表示します。
- 統合版は `ui/hud_screen.json` のアクションバーに金縁のプレートと装飾を重ねます。Geyser 拡張 1.6.0 以降は、必要な紋章アセットを登録した接続で、受取人専用のリザルト TextDisplay に `astralrecord:combat_result` モデルを割り当てます。既存の本文表示を使い、紋章はクライアントで弾んで光り、フェードします。
- パックを使えない接続は各版の通常テキストで読めます。更新は両パックを公開した後に RPG Plugin と Geyser 拡張へ反映し、クライアントを再接続します。

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

輪郭、8方向のスキル専用アイコン、金色の選択マーカー、クールダウンゲージと操作時間ゲージを `astralrecord:action_ring_panel` の1体で表示します。1番は真上で、時計回りに8番まで並びます。クールダウン中はアイコンを暗くし、残り割合をゲージで表示します。名前、枠番号、発動不可の理由と秒数は RPG プラグインが文字として重ねます。

Java 版は管理パックの適用成功後、配布マニフェストの `java.actionRingPanel` が RPG プラグインのスプライト台帳と一致する接続で一体型モデルを使います。旧管理パックは専用輪郭と個別アイコン、固定 URL やパック適用前は個別アイコンと状態ラベルで表示します。統合版は Geyser 拡張が接続時のパックにあるスプライトだけを一体化します。一体型モデルを利用できない場合は、8方向の水色の目印と個別アイコン、文字の操作時間バーを表示します。

### 武器通常攻撃

発動時に斬撃、打撃、突き、矢、ワンドの閃光、スタッフの紋を5 tick表示します。ボウ・ショートボウ・ロングボウは矢の演出を共用します。Java版は `astralrecord:weapon_attack` フォントの字形を使います。統合版はGeyser拡張 `1.7.0` 以降が `astralrecord:weapon_attack` のモデルを選び、クライアントで拡大・フェードさせます。どちらも閲覧者専用の表示で、ワールド内に演出用エンティティを追加しません。

Java版の管理パックを適用していない場合は標準粒子を1つ表示し、旧管理パックでは星を表示します。統合版で対応パックを利用できない場合も腕振りと効果音で発動を確認できます。両版の新しいパックをAPIに配置してから、RPG PluginとGeyser拡張を更新してください。表示確認は通常攻撃が使用できる武器で、空振りと命中、粒子OFF、別のプレイヤーからの見え方を各版で確認します。

## アイテムモデル

Filebase の `icon` が基本素材、`customModelData` がモデル番号です。Paper はクライアントへの送信アイテムに番号を適用します。ビルドは `item-models.json` をもとに、JE 用の振り分けと、Geyser が BE 用パックから読み込む登録情報を生成します。パックとマスターの番号が一致すると、JE は該当の立体モデル、BE は対応する手持ちモデルとインベントリアイコンを使います。未登録の番号は各版の基本素材で表示します。

1. 台帳の `id` に小文字のアセット名、`base_item` にマスター `icon` の Minecraft ID、`custom_model_data` に同じモデル番号を設定します。モデル番号は 1～16777215 の整数で、アイテム間で重複させません。`java_virtual_trident` は両版の長押し表示に参加する武器なら `true`、通常表示のみなら `false` を明記します。
2. Java Block/Item の書き出し先は `assets/astralrecord/models/item/custom/<id>.json`、テクスチャは `assets/astralrecord/textures/item/custom/<id>.png` です。新しい基本素材を使う場合は `java-item-fallbacks/<base_itemのパス>.json` に対象クライアントのバニラ定義を置きます。弓は `<id>_pull_0.json` ～ `<id>_pull_2.json` も用意します。
3. BE では `bedrock/models/entity/items/<id>.geo.json`、`bedrock/textures/entity/items/<id>.png`、`bedrock/textures/items/<id>.png`、`bedrock/attachables/<id>.json`、`bedrock/animations/<id>.animation.json` を使います。attachable の識別子は `astralrecord:<id>` です。パックは `astralrecord.<id>` のアイコンキーを生成します。
4. Blockbench の編集元は `blockbench/items/` に保存します。変更後は両版の書き出しと BE のインベントリアイコンも更新します。制作手順は [アイテムモデル制作スキル](../.codex/skills/astralrecord-item-model-author/SKILL.md) を参照してください。

JE で素材の振り分けだけを確認する場合は `/give @s minecraft:wooden_sword[minecraft:custom_model_data={floats:[21001.0f]}]` を使えます。通常攻撃と既存アイテムの表示更新は、ゲーム内でマスター由来のアイテムを使って確認します。

ノクスソードは 21001、ノクスボウは 21002、ノクススタッフは 21003 を使います。通常攻撃の腕振りと戦闘エフェクトはサーバー処理に同期します。BE の手持ちモデルは腕振りの進捗に合わせて動きます。バニラの弓の使用状態では、JE は3段階の引き絵、BE は弦の変形を使います。

### 武器の握り位置

`java_virtual_trident: true` の武器は、台帳の `grip` に Java モデル座標の握りの中心を `[x, y, z]` で登録します。モデルは握りから先端が +Y 方向になるように作ります。ビルドは握りが形状の内側にあること、一人称・三人称それぞれの左右の手で基本素材の基準に収まることを確認します。位置はモデルの 0.5 px 以内、向きは基準ベクトルとの内積 0.95 以上です。弓では引き段階にも同じ検証を行います。新しい基本素材の武器には `item-model-grip-targets.json` に4つの表示姿勢の基準を登録します。

BE の対応するモデルは `grip` ルートを `[0, 0, 0]` に置き、`q.item_slot_to_bone_name(context.item_slot)` で手にバインドします。握りのキューブはこの原点を含め、待機アニメーションで `grip` を `[0, 24, 0]` 付近に置きます。`first_person` と `third_person` はそれぞれ `context.is_first_person` と `!context.is_first_person` で1回ずつ有効にし、待機中はループさせます。縮尺を指定する場合は全軸を 0.5～2.0 に収めます。攻撃や弓を引く動作は待機姿勢の `grip` を維持します。

ビルドはモデルの +Y 軸を基準に姿勢を検証します。一人称の主手では通常の腕の回転を合成し、上向き成分を 0.8 以上、奥行き成分の絶対値を 0.5 以下にします。三人称では手のローカル座標で前向きの成分を 0.8 以上にします。形状の先端と刃や弓の面は Blockbench でも確認します。[Mojang の一人称アニメーション](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/player_firstperson.animation.json)

アクションリングの長押し設定が有効で候補が2つ以上の場合、本人の選択中の武器を仮想トライデントとして送ります。JE は `java_virtual_trident` を指定した武器の待機モデルを表示できます。パック形式 83 以降もオーバーレイで通常のトライデント表示を維持します。

BE では仮想トライデントで長押し入力を受け取りながら、主手の表示を `astralrecord:held_weapon_model` のモデル番号で切り替えます。番号が 0、対象武器の登録外、または副手の場合は、バニラのトライデントのモデルと使用アニメーションを使います。ビルドは `java_virtual_trident: true` の武器だけで切り替えを生成し、Geyser 用カタログに各アイテムの `held_weapon` を出力します。各武器の attachable の `first_person`、`third_person`、`attack` とその条件を再利用します。これらが使う変数は `scripts.pre_animation` で、配列の各要素につき1回の変数代入で設定します。弓の引き絵は実際に弓を使用しているときに適用されます。 [Mojang のトライデント定義](https://github.com/Mojang/bedrock-samples/blob/a3b394c507a6b11a3c6f61552e778ff5c4b89fd2/resource_pack/attachables/trident.entity.json)

新しい BE 登録はパックを API に配置した後の Proxy 再起動で有効になります。

### 2Dアクセサリー

アクセサリーのピクセルアイコンを配布するときは、台帳に `render_mode: "generated"` を指定します。省略または `"3d"` は従来の立体モデルです。`generated` は `java_virtual_trident: true` や `grip` と併用できません。

入力は `assets/astralrecord/textures/item/custom/<id>.png` と `bedrock/textures/items/<id>.png` の2枚です。ビルドはJEの `minecraft:item/generated` を親にしたモデルを生成し、BEではアイコンとアトラスを登録します。Blockbench編集元や手持ちattachable、geometry、animationは要求しません。カタログの `render_mode` を読む対応Geyser拡張が必要です。アクセサリースロットの装備は性能に反映され、体にモデルを取り付ける処理は追加しません。

原画は [blockbench/pixel-art](blockbench/pixel-art/README.md) のパレット付きJSONで編集できます。`python scripts/generate-pixel-item-assets.py --check` で、全編集元と両版PNGのバイト一致を検証できます。

未登録の同素材を維持するため、基本素材ごとのバニラ定義を `java-item-fallbacks/` に用意します。プレイヤーヘッドは形式83以降の座標変換が異なるため、`transformed_item_models/player_head.json` も使います。時計の時刻表示、プレイヤーヘッドのスキン、壁などのブロック形状を含め、fallback全体を保持します。

## ビルド

このディレクトリで以下を実行します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-resourcepack.ps1
```

`dist/AstralRecordResourcePack.zip`（Java）と `dist/AstralRecordResourcePack.mcpack`（Bedrock）を生成します。出力先は `-OutputDirectory <dir>` で指定できます。同じ入力ファイルからは同じバイト列を生成し、Bedrock の配布内容が変わるとパック UUID も変わります。生成したアーカイブはコミットしません。

字形の修正時は `scripts/generate-damage-number-assets.py` を Python で実行し、両版の PNG と関連 JSON を再生成します。通常のパックビルドに Python は不要です。

輪郭を変更するときは `scripts/generate-action-ring-assets.py` で両版の PNG とモデルを再生成します。128 px の面に対するアイコン中心の半径は42 px、輪郭の最大半径は61 pxです。寸法や枠の色を変えるときは `SkillActionRingService`、`SkillActionRingDisplay` と Geyser 拡張の `ActionRingFrame` も確認します。

スキルアイコンは `scripts/generate-action-ring-icons.py` で生成します。生成元の既存番号を保持し、新しいスキルは未使用の番号に追加します。ビルドは RPG プラグイン内の `action-ring-icons.json` と両版のテクスチャをもとに一体型モデルを生成します。台帳にないスキルは従来のアイテムアイコンを表示します。

通常攻撃の絵柄を変更するときは `scripts/generate-weapon-attack-assets.py` で両版の16 pxスプライトを再生成します。

## サーバー配布

1. 初回は [Geyser Extension の導入手順](../10_plugin/AstralRecordGeyserExtension/README.md) に従い、Velocity の拡張を更新します。起動時と以後60秒ごとにAPIのBedrockパックを確認し、新しい接続へ配布します。
2. API・Web・Plugin を有効にした設定で [01-deploy-debug.bat](../60_tool/deploy-debug/README.md) を実行します。API の配置物に両パックと最新ハッシュを含めます。
3. Java 版はゲームサーバーから公開WebのダウンロードURLを受け取ります。配布先を変える場合は RPG Plugin の `resourcePack.managed.publicBaseUrl` を設定します。統合版は Geyser の接続時にパックを受け取ります。更新したパックの適用は再接続時です。

アクションリングの導入時は、新しい両版パックを API へ公開してから、対応する RPG Plugin と Geyser Extension を更新します。

RPG Plugin の `resourcePack.bedrockNamePrefixes` は、Velocity の Floodgate の接続名接頭辞と一致させます。現行環境は `.` です。
