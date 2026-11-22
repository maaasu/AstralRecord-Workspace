# AstralRecord リソースパック

Java Edition と Bedrock Edition に戦闘 HUD、討伐リザルト、ダメージ数値、アクションリング、アイテムモデル、武器通常攻撃と釣りの演出、GUI共通アイコンを配布します。配布するアイテムの外観は `item-models.json`、GUI専用の155種・203割り当ては `gui-icons.json` で管理します。

## GUI

所持品、チェスト系メニュー、作業台などを、紺と金の枠と天体の装飾で揃えます。Java 版の Plugin 管理の1～6行チェストは、互換版1の管理パックの適用成功後に、暗いタイトル帯とクリーム色の文字を送信時だけ重ねます。通常のチェストや作業画面はクライアントのテクスチャを使います。統合版は Classic・Pocket の各画面に背景を指定し、チェストを使う Plugin メニューにも適用します。

通常の所持品には「AstralRecord」の銘板を表示し、2×2 クラフト欄の矢印と見出しを隠します。Java 版は通常の所持品だけでレシピボタンを背景に溶け込ませ、作業台・かまど類では紺色の本として表示します。ボタンの操作は既存どおりです。統合版 Classic は緑の本を天体のアイコンに差し替え、Pocket はクラフトタブを使います。

ページ移動、確認、取消などの共通操作は紺の台座と金縁で揃え、機能別のアイコンと透過ダミーは既存の絵柄を使います。プレイ中の HUD ホットバーは青灰色で揃え、選択中は金縁を青緑の光が巡ります。対応画面は [GUI対応表](GUI_COVERAGE.md) に記載しています。

ダンジョンのカルトグラフは、部屋の入口・攻略済み・進入可能・進行中・未発見、現在地の8方向、通路の接続方向と開閉を16pxの専用図形で示します。両版で同じ32px配布テクスチャを使い、標準のチェストスロットと部屋選択の操作を維持します。`scripts/generate-cartograph-gui.py --check --preview <PNGパス>` で原画と両版配布画像を確認できます。プレビューの出力には Pillow が必要です。

原画の出典と再生成方法は [gui-source](gui-source/README.md) にあります。両配布パックへ `NOTICE-GUI.txt` を含めます。共通アイコン、両版の背景、Java の所持品とコンテナは、各生成スクリプトの `--check` で検証し、`--preview <path>` で素材を使った配置図を出力できます。HUD ホットバーは `generate-java-hotbar.py` と `generate-bedrock-hotbar.py` で検証できます。

## 対象と構成

- Java Edition 1.21.11〜26.3（リソースパック形式 75.0〜97.1）は `astralrecord:damage_number` フォントを使います。縁付きの数字と記号を収録し、色と動きはサーバープラグインで設定します。
- Bedrock Edition は `astralrecord:damage_number` クライアントエンティティを使います。数字をカメラに向けて中央に揃え、上昇しながら縮小・フェードさせます。

### 冒険記録のモブの頭

冒険記録の一覧と詳細では、モブの外見に対応する頭を表示します。ゾンビ、スケルトン、ウィザースケルトン、ピグリンはバニラの頭アイテムを使います。その他の現行モブ28種、色違い4種、専用ボス3種には `mob_head_*` の35種類のGUIアイコンを用意しています。バニラのクリーパーとエンダードラゴンの頭も選択に対応します。

追加分は実モデルの頭部とテクスチャを斜め上から描画した32px透過PNGで、Java・統合版で同じ絵柄を使います。サンバード、セレネア、ニクセロンは既存の専用モデルから頭部・顔の部分を抽出します。JavaはGUIモデル、統合版は既存Geyserのgeneratedアイテム経路を使用し、ゲーム内のモブ本体は変更しません。Pluginと両版パックを一緒に更新し、統合版ではパックのカタログを読み込むProxyを再起動してから再接続します。

原稿・出典は [gui-source/mob-heads](gui-source/mob-heads/README.md) を参照してください。`scripts/generate-mob-head-icons.py --check --preview <PNGパス>` で35種の両版配布PNGと16pxスロットの見本を検証できます（Pillow・NumPyが必要）。プレビューは配布用描画データによるオフスクリーン画像です。実クライアントの表示確認とは区別します。

### クエスト追跡HUD

対応パックの接続では、受注中の依頼名と条件ごとの進捗を左上に自動表示します。Java版は背景の描画を暗色・不透明度約52%とし、依頼名の帯、真鍮色の枠、進捗アイコンを重ねます。統合版は従来の透明な内側と半透明の外枠を維持します。受注順に最大3件・計10行を使い、収まらない依頼や条件は種類別に件数を表示します。Javaは独立した4倍解像度のクエスト専用ビットマップ字形を使い、専用フォントの大きな負ascent、GUI判定、RGB座標マーカーを同時に満たした頂点だけを再配置します。各字形の字送りは直後の負spaceで相殺し、通常ActionBarの中央寄せ幅を変えません。低い画面では既存の個人情報HUDを避けるため、クエスト欄の位置と倍率を調整します。75.0、84.0、88.0、97.1の描画経路に対応するoverlayを持ちます。コアシェーダー上書きはMojangの非サポート機能なので、新版追加時は公式資産との差分とコンパイルを再検証します。

統合版は `geyseropt:astralrecord_quest_hud:` のTIPキーだけをJSON UIで保持し、chat_stack内の座標・ペーパードール余白の後、チャットの前に表示します。本文の行数に応じて枠の高さが変わります。通常TIP、ActionBar、Title、Sidebarを上書きしません。両版とも `quest-tracking-hud-version.txt` の互換版2を使用し、Javaは配布manifestの `java.questTrackingHud` と適用成功、統合版は接続時の資産検証とログイン後のProxy通知を必要とします。表示対象なし・設定OFF・バックエンド切替・更新途絶は専用値で消去します。対応接続では「プレイヤー設定 → クエスト追跡HUD表示」で変更できます（初期値ON）。

`python scripts/generate-quest-hud-java.py --check --preview <PNGパス>` と `python scripts/preview-quest-hud-bedrock.py --preview <PNGパス>`（プレビューはPillowが必要）で配布字形・描画定義から静的画像を生成します。Java版の追加字形は [Noto Sans CJK JP Bold (Sans2.004)](https://github.com/notofonts/noto-cjk/tree/Sans2.004) を元にした改変ビットマップです。OFLの著作権表記とライセンスは `licenses/OFL-NotoSansJP.txt` を同梱します。対象4版の正規クライアントJARと Pillow 13.0.0／FreeType 2.14.3 を用い、同スクリプトに `--clients-dir <JARのフォルダ> --raster-font <NotoSansCJK-Bold.ttc>` を渡して再生成できます。統合版の画像はネイティブ書体をJavaパック字形で代用した配置図です。実クライアントでの描画確認とは分けて扱います。

### アクションリング CT HUD

Java版の管理パックで `java.actionRingCooldownHud = "1"` の適用に成功した接続だけに表示します。ホットバーの右側に、装備済みのリング枠だけを実際の角度（上から時計回り、最大8枠）に配置します。金色の弧は各枠の残り割合、数字は切り上げた残り秒数（最大999秒。1000秒以上は999+）、青緑のひし形は使用可能、灰色の点はクールタイム以外の使用条件が未成立の状態です。空き枠は描画しません。戦闘HUDの右に十分な余白があれば通常サイズで表示します。余白が少ない場合はホットバーの高さに縮小し、右側に出るオフハンド枠と現在地HUDを避けて配置します。表示寸法を確保できない場合は表示を省略します。

`python scripts/generate-action-ring-cooldown.py --check --preview <PNGパス>` で既存のカウントダウン素材を使う専用フォントとアトラスを検証できます。座標、既存HUDとの分離と4世代のシェーダーは `generate-quest-hud-java.py --check` で検証します。プレビューはゲーム内撮影ではありません。

### 現在地HUD

Java 版は配布マニフェストの `java.locationHud` が `4` または `5` の管理パックを適用し、個人情報 HUD を描画できる状態で、現在地・挑戦状況・効果を右上に表示します。ワールド名、エリア名とレベル、任意の通信・性能情報は、背景枠を使わず影付きの文字で右端を揃えます。エリアLvは金色、MSPTとPingは値に応じて緑・黄・赤で表示します。日本語は Noto Sans JP の4倍解像度ビットマップ字形、収録済みの英数字・記号はアクションバーと同じ字形を使います。効果アイコンは既存のバフアイコンの絵柄を使います。専用 RGB マーカーと負の ascent を使って4世代のテキストシェーダーで描画し、画面が狭い場合は左上と下部の HUD との重なりを避けるため縮小します。ビルドと配布時は字形アトラスのSHA-256を照合します。`scripts/generate-location-hud-text.py --check` で字形と送り幅を、`scripts/generate-quest-hud-java.py --check` でシェーダーと配置を検証できます。

互換版5では性能情報の下に装備スキルのCT一覧を追加します。最大12アクティブ枠と重複しない左クリック1枠を表示し、CT中は黄色の秒数、一般的な発動条件を満たすと緑の「準備完了」を使います。最大32行へ拡張し、ボス・ダンジョンと効果のページ表示を維持します。互換版4の適用済みクライアントには従来の16行用マーカーを送り、CT一覧は表示しません。

### 戦闘 HUD と討伐リザルト

- 対応パックを適用したJava版では、戦闘アクションバーの左上に本人のレベル・所持Goldと、正式クラス名・レベル・EXPの2行を専用フォントで重ねます。統合版の個人情報はサイドバーに表示します。
- 両版の標準HUDも戦闘行に合わせて、HPはハート、吸収ハートのMPは雫、満腹度のENGは稲妻で表示します。MPの半分表示は吸収ハートの仕様に従い、残量ゼロでは雫が消えます。

- Java 版は管理配布パックの適用成功後に `astralrecord:combat_hud` を使います。通常アクションバーの HP・MP・ENG は専用アイコン、現在値と最大値、10段階ゲージで表示します。個人情報HUD対応パックでは専用の等幅数字を使い、ゲージを数値の下に並べます。配布マニフェストの `java.wideCombatMeter` が `1` のパックでは、24ピクセル幅のゲージ字形を使います。個人情報HUDの互換版3以降では幅5ピクセルの等幅数字と、アイコンや数値の間に広い余白を使います。戦闘の補足情報はリソースの下の行へ分けます。互換版4では正式クラス名と、戦闘行の右上に状態異常アイコンと残り秒数を表示します。正式名の字形はOFLのDotGothic16を用い、`scripts/generate-class-name-hud.py` で再生成できます。書体と字形は `scripts/generate-gameplay-hud.py` で再生成できます。HP が25%以下のときは通常の10 tick 更新でゲージが明滅します。従来のリザルト本文では紋章が光る見出しと金の区切り線を使います。
- 互換版5以降では、戦闘行と個人情報にMinecraft標準に近いピクセル字形と白・灰の数値を使い、個人情報の項目は空白で分けます。現在有効なバフ・デバフは、状態異常の秒数と重ならない位置に最大6個のアイコンで並べます。
- 互換版6では、HP・MP・ENGの間隔を数値の桁に応じて調整し、通常は約268ピクセルに広げます。個人情報をその左端付近に寄せ、クラス名に高さ最大9ピクセルの小さい日本語字形を使います。
- Java版の戦闘・個人情報・状態異常の文字と、互換版6のクラス名は、表示寸法と送り幅を保った2倍解像度のビットマップを使います。クラス名は18ピクセル以内の原画を9ピクセルの高さに収め、GUIスケールが小さい場合にも細い線を判別しやすくします。
- 統合版は `ui/hud_screen.json` で通常のテキストアクションバーに金縁のプレートを描画し、HP・MP・ENG のラベルと数値を表示します。Geyser 拡張 1.6.0 以降は、必要な紋章アセットを登録した接続で、受取人専用のリザルト TextDisplay に `astralrecord:combat_result` モデルを割り当てます。既存の本文表示を使い、紋章はクライアントで弾んで光り、フェードします。
- 報酬プレートは不透明度28%の紺地、通常の金縁、レアの紫縁を使います。プレートは短く浮き上がって静止し、レアがある場合だけ右上が一度光って、表示の最後に薄くなります。Java版は `java.combatRewardPlaque` が文字列 `"1"` の管理パックの適用成功後に、`astralrecord:combat_reward_plaque` フォントで背景と本文を重ねます。統合版は本文を一度に表示し、枠の高さを行数に合わせます。互換版2の統合版パックを Geyser 拡張 1.21.0 と AstralRecordProxy 1.6.4 以降で配布すると、RPG が指定した表示時間の最後の0.5秒で枠が薄くなります。
- `scripts/generate-combat-reward-plaque.py` はロアの `purpleBorder.png` を9分割で伸ばし、Java版の5〜10行用の枠と統合版の伸縮用テクスチャを生成します。共通アイコンには `tooltip_icons.png` の絵柄と、毛皮・肉の報酬用アイコンを使います。両パックに `combat-reward-plaque-version.txt` を収録します。`--check` で配布素材を照合し、`--preview <PNGパス>` で枠とアイコンの画素を確認できます。
- Java版と互換版3の統合版では、表示内容に合わせて報酬枠を横へ広げ、品名・個数・確率を1行に表示します。複数ページの枠は同じ幅を使います。統合版で横幅の指定を反映するには Geyser 拡張 1.22.0 と AstralRecordProxy 1.6.5 以降が必要です。
- パックを使えない接続は各版の通常テキストで読めます。更新は両パックを公開した後に RPG Plugin と Geyser 拡張へ反映し、クライアントを再接続します。

### 苔骸の墓守 モルグラス（苔むしたゾンビ）

ミドルアース遺跡のボス `morgras` は、苔むした成人ゾンビの近接ボスです。氷球・突進・シールドは使いません。ボス枠とクリア報酬は従来どおりです。

Java の歩行は水平方向の移動距離に合わせ、待機・左右の歩行と中間姿勢を1 tickごとに選びます。歩き始め・停止時は振り幅を段階的に変え、技の姿勢は歩行より優先します。従来のパックでは既存の左右モデルに戻ります。統合版の既存ボーン補間は変更していません。

技は苔腕の叩きつけ・苔胞子だまり・苔むす地鳴り（HP50%以下）の3つで、予兆中は腕の振り上げ・両腕を広げる咆哮、発動時は振り下ろしの姿勢を表示します。Java は `custom_model_data` の `raise`・`slam`・`roar` で姿勢モデルを選び、統合版は `astralrecord:motion`（1～3）で同名のアニメーションを補間して重ねます。姿勢モデル、アイテム定義の分岐、統合版アニメーション、Blockbench の統合版編集元は `scripts/generate-moss-zombie-poses.py` が待機モデルとボーンの回転中心から生成します。`--check` で配布物との一致、`--preview <PNGパス>` で両版の待機・3姿勢を正面斜めと側面から描画できます（Pillow が必要。例: `uv run --no-project --python 3.12 --with pillow==13.0.0 python scripts/generate-moss-zombie-poses.py --check --preview out.png`）。Java の姿勢モデルは振り上げた腕を座標範囲に収めるため形状を0.75倍にし、表示倍率を同じ比で大きくしています。

技の予告と着弾には専用演出を重ねます。地鳴りは琥珀色の縁と苔の房・遺跡の石を並べた予告輪、胞子だまりは胞子の舞う予告輪、叩きつけは扇形の左右の縁と弧を這う苔の帯でなぞります。着弾では地面から苔の棘（叩きつけは扇形の内側8か所、地鳴りは円内17か所）、胞子だまりでは胞子の破裂が伸び上がります。形状は `warning_ring`・`warning_spore`・`warning_vine`・`impact_spikes`・`impact_spores` の5種（統合版の `astralrecord:style` は0～4）で、すべて長さ方向に対称です。向きのある扇形はサーバーが複数の帯と棘を並べて表すため、両版で向きの扱いが変わりません。Java は4フレームの ItemDisplay を寸法に合わせて拡大し、統合版は `astralrecord:midgard_moss_zombie_skill` を Molang で補間します。両版の演出は `scripts/generate-moss-zombie-effects.py` が生成し、`--check --preview <PNGパス>` で全形状・全フレームを真上と低い横からの視点で描画できます。危険範囲の正確な形と攻撃判定は従来の床粒子と同じで、演出はパック対応の接続にだけ表示します。

Java の外観・攻撃演出は、配布マニフェストの `java.mossZombiePresentation` が文字列 `"2"` で、パックの適用に成功した接続へ表示します。API もこの互換情報を保持する必要があります。ボスID変更時は [DB移行手順](../60_tool/13-db-migrate/README.md) で既存の討伐記録を引き継ぎます。反映には `v1.morgras.yml` とダンジョン参照を同時に配布してマスターを再投入し、新IDに対応したPluginを使って新しくダンジョンへ入場します。

統合版の姿勢と演出には Geyser 拡張 1.33.0 以降が必要で、古い拡張やパック非対応の接続でも予告粒子と攻撃判定は同じです。

Java はパック対応接続で待機・2つの歩行姿勢を持つ ItemDisplay を追従させ、追跡が揃ってから実体の描画だけを隠します。統合版は対応パック・Geyser 拡張がある接続だけ `astralrecord:midgard_moss_zombie` へ置換し、ボーンの歩行をクライアントで動かします。両版とも攻撃・当たり判定は通常ゾンビの実体を使い、パック非対応の場合はバニラの成人ゾンビが見えます。実クライアントでの描画は未確認です。

### 陽冠のサンバード

小さな胴と長い翼を基準に、藍色の羽と金色の陽冠・胸の太陽紋で輪郭を作ります。翼は肩と風切羽を別々に動かし、待機・飛行・溜め・振り下ろし・復帰を連続させます。両版の編集元は [blockbench/entities](blockbench/entities) にあり、128×128 px のテクスチャを埋め込んでいます。

Java 版の対応パック（`sunbird-presentation-version.txt` の値が `2`）では、本体・左右の翼・左右の風切羽を5つの ItemDisplay で合成します。2 tick 間隔で親子関節を計算し、クライアントで補間します。被弾時は従来どおり赤いティント、無敵時は金の発光です。対応パックが適用済みで、全パーツの追跡が揃った接続だけサーバー実体を隠します。互換版1には従来の姿勢モデル、パック非対応の接続には従来のパロットと粒子を使います。

統合版は `astralrecord:sunbird` のボーンで翼をつなぎ、従来通りのモーション、yaw、無敵の通知を使います。ルートでパロットの表示倍率を補正し、無敵中は光輪を独立して表示します。制作と検証の基準は [モブモデル制作スキル](../.codex/skills/astralrecord-mob-model-author/SKILL.md) を参照してください。

スキル演出の統合版エンティティは `astralrecord:sunbird_skill` です。`astralrecord:style` が形状を、`width`・`height`・
`length` が各軸の倍率を指定します。赤橙の危険範囲と青緑の安全円は、床の目印と高さのある外枠を組み合わせます。
外枠は予兆中の位置を保ちます。着弾時は火柱と衝撃波が中心から外へ広がり、ビームと突進では厚みのある火の波が
帯の長手方向（Z 軸）へ走ります。Java 版の対応パックは形状を入れ替えずにテクスチャの明暗と着弾の変換を補間し、互換版1では従来の4段階表示を使います。統合版はクライアント側でボーンを補間します。判定寸法、攻撃の時刻、攻撃範囲は変えません。

| スタイル値 | Java 版の `astralrecord:` に続くモデル名 | 形状 |
| --- | --- | --- |
| 0 | `sunbird_warning_circle` | 円の警告 |
| 1 | `sunbird_warning_line` | 直線の警告 |
| 2 | `sunbird_warning_boundary` | 隕石の影響範囲の外周 |
| 3 | `sunbird_impact` | 火柱 |
| 4 | `sunbird_warning_safe` | 安全圏 |
| 5 | `sunbird_impact_line` | 直線の火光 |

両版のパックを更新して配布します。統合版の姿勢・無敵状態の通知には Geyser Extension 1.15.0 を使います。対応アセットのある接続で専用モデルを表示し、旧パックの接続では利用できる従来表示を使います。

対応パックの Java モデル名には上表の名前に `_v2` が付きます。検証では配布モデルと実行時の変換・ボーン動作を使ったオフスクリーン描画を、実クライアントの描画とは区別します。この改修の Minecraft 実クライアントでの描画は未確認です。

### ボス本体の向きの同期

サンバードとセレネアは、サーバー本体のyawを姿勢・無敵と同じ通知に載せ、統合版の `astralrecord:body_yaw_tenths` に渡します。ルートボーンでクライアントの `query.body_y_rotation` を差し引き、サーバーの向きへ最短角度で補間します。これにより、元のパロット／ウィッチの向き補間が専用外観へ重なることを防ぎます。送信は既存の5tick更新で、向きまたは姿勢が変わった場合と追跡開始時だけ行います。Java版の本体表示・当たり判定・テクスチャUVは変更しません。

この修正にはRPGプラグイン、Geyser拡張、統合版パックを一緒に更新し、Proxy再起動後にパックを再適用します。旧通知（yawなし）や旧拡張でプロパティが存在しない場合は従来の向きを維持し、旧パックは追加プロパティを無視します。実クライアントでの見え方は通信・オフスクリーン検証と区別します。

### 月蝕の綴り魔女 セレネア

`selenea_eclipse_witch` は三日月の帽子、紫のローブ、開いた魔導書を持つモデルを使います。Java 版は ItemDisplay で待機・移動・詠唱・発動・復帰の姿勢を切り替え、被弾・無敵中は装飾の色を変えます。統合版は `astralrecord:selenea` で姿勢を補間し、無敵中は帽子の周りに光を回します。両版の編集元は [blockbench/entities](blockbench/entities) にあります。

墨の三叉、呪頁の落款、月輪、月蝕の通路と大術、封頁、通常波、三連弾に専用演出を重ねます。危険範囲は紫の立体枠、退避・解除の目印は青緑で表示し、通常波は床面を低く広がります。統合版の `astralrecord:selenea_skill` は `style` で形状を選び、`width`・`height`・`length` で各軸の寸法を受け取ります。対応パックでは、写本守への転送門に円環のアーチを、月蝕大術と封頁失敗の月蝕では会場上空に月蝕の円盤を表示します。技の配布アセットは `python scripts/generate-selenea-skill.py --check` で検証できます。

両版のパックに互換マーカー `selenea-presentation-version.txt` を収録します。Java 版は配布マニフェストの `java.seleneaPresentation` が文字列 `"1"` の管理パックの適用成功後に表示します。統合版は対応パックを配布した接続で Geyser 拡張 1.18.0 と AstralRecordProxy 1.6.0 を経由して表示します。非対応の接続ではウィッチと従来の予兆を使います。

本体は Blockbench Web、技は両版の配布データをオフスクリーンで描画し、姿勢、境界、発動時の動きを確認しました。Minecraft 実クライアントでの描画は未確認です。

### 天球心臓 ニクセロン

`orrery_heart_nyxeron` は、黒曜石の心臓を銅・緑青・アメジストの三本の環が囲む天球儀の姿です。心臓は四方に光る眼、紫の脈、金の帯と極の棘を持ち、周りを4つの星屑が回ります。予告では眼が開いて花弁が半分開き、発動では花弁が全開になって星屑が外へ広がり、無敵の封印調律ではアメジストの結晶籠に閉じ、露出では殻が割れて金の核が現れます。

Java 版は戦闘用のアイアンゴーレムを閲覧者ごとに不可視にし、核（`nyxeron_core`、`custom_model_data` の先頭値 0～4 が通常・予告・発動・調律・露出）、三本の環（`nyxeron_ring_outer`・`_mid`・`_inner`）、花弁（`nyxeron_petal` ×4）、結晶籠（`nyxeron_cage`）、星屑（`nyxeron_mote` ×4）を別々の ItemDisplay で表示します。プラグインが2tickごとに3tickの補間で目標姿勢を送り、花弁の開閉・籠の出現・星屑の軌道も含めて途切れずに動かします。テクスチャ `nyxeron.png` は8コマ・3tick間隔、`nyxeron_skill.png` は8コマ・2tick間隔の補間付きアニメーションで、脈・眼・危険範囲の縁が明滅し、帯の破線が流れます。被弾中は核を赤、調律中は紫、露出中は金に染めます。統合版は `astralrecord:nyxeron` が姿勢番号と無敵状態から同じ傾き・速さ・開き具合を Molang 変数で補間します。

技は24形状です。中心円・帯4種・光条・封印板・調律済み・星痕・衝撃・視線・星墜・心拍（0～12）に、縮む予告輪（13）・伸びる予告帯（14）・星弾（15）・低い衝撃環（16、青）・高い衝撃環（17、ピンク）・低い光刃（18）・高い光刃（19）・星片（20）・落ちる星（21）・星環弾の輪（22、統合版だけ）・超新星（23）を加えました。Java 版は `nyxeron_<形状>` の1モデルを ItemDisplay の寸法と表示補間で動かし、統合版は `astralrecord:nyxeron_skill` の `style`（0～23）、`width`・`height`・`length`、`param`（残りtick、光刃の回転速度、星環弾の隙間）で同じ動きを再生します。危険範囲は赤紫、予告輪は金、跳んで避ける攻撃は青、しゃがんで避ける攻撃はピンク、封印板は金、調律済みは青緑、星痕は紫、星片は水色です。正確な判定範囲は床の粒子が引き続き示します。

効果音は `assets/astralrecord/sounds/boss/nyxeron/` の36種で、`boss.nyxeron.*` として登録します。攻撃ごとの予告音と着弾音、発動前の刻み、弾・衝撃環の発射音、星片、超新星、調律、段階の移行を合成したモノラル Ogg Vorbis です。統合版の `sound_definitions.json` へは `hostile` 分類で同じIDを書き出します。

両版の形状・テクスチャ・アニメーション・効果音は `scripts/generate-nyxeron-assets.py` が生成し、これを編集元とします。Ogg のストリーム番号を固定し、同じ入力から同じバイト列を作ります。`uv run --no-project --python 3.12 --with pillow==13.0.0 --with numpy==2.2.6 --with soundfile==0.13.1 python scripts/generate-nyxeron-assets.py --check` で配布アセットの一致、使わなくなったモデルや音の残り、Java のキューブ範囲・回転角、統合版のボーン階層、音声の形式を検査できます。両版のパックに互換マーカー `nyxeron-presentation-version.txt`（`2`）を収録します。Java 版は配布マニフェストの `java.nyxeronPresentation` が `"2"` の管理パックの適用成功後に表示します。統合版は Geyser 拡張 1.29.0 と AstralRecordProxy 1.6.8 以降を経由して表示します。互換版1のパックや旧拡張の接続、非対応の接続ではアイアンゴーレム、粒子の予兆と弾、標準音を使います。

両版の配布データを独立したソフトウェア描画（面の平均色）で5姿勢・4方向と24形状を描き、Java と統合版の輪郭・環の傾き・花弁と籠と星屑の位置・形状が一致することを確認しました。統合版の Molang 変数とボーン参照は静的に検査しました。Minecraft 実クライアントでの描画、統合版の光刃の回転の向き、効果音の聞こえ方は未確認です。

### 不死鳥との共鳴

`archmage_phoenix_resonance` の召喚中は、炎の翼と三本の尾羽を持つ `astralrecord:archmage_phoenix` を表示します。両版の編集元は [blockbench/entities](blockbench/entities) にあり、同じ 128×128 px のテクスチャを使います。

Java 版は ItemDisplay の FIXED 表示で、`custom_model_data` の浮動小数点の先頭値から水平（0）、上（1）、下（2）の姿勢を選びます。3姿勢は統合版のリグから書き出し、Java 1.21.11 の複数軸回転で翼と尾羽の角度を保持します。統合版はクライアントエンティティで1.2秒の羽ばたきを補間して繰り返します。

両版の配布パックに互換マーカー `archmage-phoenix-presentation-version.txt` の `1` を収録します。Java 版は配布マニフェストの `java.archmagePhoenixPresentation` が文字列 `"1"` の管理パックの適用成功後に表示します。統合版は対応パックの強制配布とログイン後の通知を使い、Geyser 拡張 1.26.0 と AstralRecordProxy 1.6.7 以降を経由して表示します。非対応の接続ではオウムに粒子を間引いた炎の輪郭を重ねます。

Blockbench Web で両版の配布データを読み込み、3つの代表姿勢を前・斜め・横・背面・下から同じ寸法で描画しました。統合版は中間フレームも確認しました。Minecraft 実クライアントでの描画は未確認です。

### 釣り

釣り竿の先端からウキの真鍮の環まで、面ごとに明暗をつけた細い立体の釣り糸を伸ばします。ウキは赤とクリーム色で塗り分け、下に金属の針を付けています。着水は青緑の飛沫と二重の波紋、魚の接近は小さな波で表します。食いつく前に距離を取ると、張った糸とウキがプレイヤー側へ引かれます。

- Java 版は ItemDisplay の `custom_model_data` の文字列で、黒色のコンクリートから `fishing_line`、トリップワイヤーフックから `fishing_hook`、空色のカーペットから `fishing_splash` と `fishing_wake` を選びます。通常アイテムにはバニラのモデルを使います。
- 統合版は Geyser 拡張を経由して `astralrecord:fishing_visual` を描画します。`astralrecord:style` の `0` が釣り糸とウキ、`1` が着水、`2` が魚の波です。釣り糸は竿先を起点に、ウキまでの相対座標 `dx`・`dy`・`dz` とたるみ `sag` をブロック単位で受け取り、10 区間でつなぎます。
- 両版の配布物に互換版 `1` の `fishing-visuals-version.txt` を収録します。 Java 版は別途 `fishing-rod-tip-version.txt` に互換版 `1` を収録し、対応パックを適用した釣り人に新しい竿先から最初の線分を表示します。`python scripts/generate-fishing-visuals.py --check` で両版のモデル、アニメーション、テクスチャを検証できます。

両版の配布データをオフスクリーンで描画し、投擲・着水・水中での引き寄せ・巻き取り時の糸とウキ、着水と魚の接近の演出を確認しました。Minecraft 実クライアントでの描画は未確認です。

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

### ダンジョンクリア音

ボス部屋クリア時は `astralrecord:dungeon.clear` の約6.2秒のファンファーレを、クリア時点でダンジョン内にいる参加者へ一度だけ再生します。承認済みプレビューと同じステレオ Ogg Vorbis を `assets/astralrecord/sounds/dungeon/clear.ogg` に収録し、統合版へも同じ音源とイベントIDを出力します。統合版の分類は `ui` です。

両版の配布パックに `dungeon-clear-sound-version.txt=1` を含め、Java配布マニフェストには `dungeonClearSound: "1"` を設定します。Java版は対応管理パックの適用成功後、統合版はGeyser拡張が専用イベント・音源・マーカーを確認し、ProxyがRPGへ対応通知した接続だけで専用音を使います。従来の `gameplay-sounds-version.txt=1` だけでは対応扱いしません。非対応の接続は標準クリア音を使います。

更新時はRPG本体・AstralRecordProxy・AstralRecordGeyserExtensionと両版パックを同時に配布し、Proxyを再起動してクライアントを再接続します。既存の `60_tool/01-deploy-debug.bat` のパック配布・マニフェスト生成経路を使います。通常部屋のクリア、失敗・中止、報酬画面の再表示ではファンファーレを再生しません。

## アイテムモデル

Filebase の `icon` が基本素材、`customModelData` がモデル番号です。Paper はクライアントへの送信アイテムに番号を適用します。ビルドは `item-models.json` をもとに、JE 用の振り分けと、Geyser が BE 用パックから読み込む登録情報を生成します。パックとマスターの番号が一致すると、両版で該当アイテムの立体モデルまたは2Dアイコンを使います。未登録の番号は各版の基本素材で表示します。

1. 台帳の `id` に小文字のアセット名、`base_item` にマスター `icon` の Minecraft ID、`custom_model_data` に同じモデル番号を設定します。モデル番号は 1～16777215 の整数で、アイテム間で重複させません。`java_virtual_trident` は両版の長押し表示に参加する武器なら `true`、通常表示のみなら `false` を明記します。
2. Java Block/Item の書き出し先は `assets/astralrecord/models/item/custom/<id>.json`、テクスチャは `assets/astralrecord/textures/item/custom/<id>.png` です。新しい基本素材を使う場合は `java-item-fallbacks/<base_itemのパス>.json` に対象クライアントのバニラ定義を置きます。弓は `<id>_pull_0.json` ～ `<id>_pull_2.json` も用意します。
3. BE では `bedrock/models/entity/items/<id>.geo.json`、`bedrock/textures/entity/items/<id>.png`、`bedrock/textures/items/<id>.png`、`bedrock/attachables/<id>.json`、`bedrock/animations/<id>.animation.json` を使います。attachable の識別子は `astralrecord:<id>` です。パックは `astralrecord.<id>` のアイコンキーを生成します。
4. Blockbench の編集元は `blockbench/items/` に保存します。変更後は両版の書き出しと BE のインベントリアイコンも更新します。制作手順は [アイテムモデル制作スキル](../.codex/skills/astralrecord-item-model-author/SKILL.md) を参照してください。

JE で素材の振り分けだけを確認する場合は `/give @s minecraft:wooden_sword[minecraft:custom_model_data={floats:[21001.0f]}]` を使えます。通常攻撃と既存アイテムの表示更新は、ゲーム内でマスター由来のアイテムを使って確認します。

ノクスソードは 21001、ノクスボウは 21002、ノクススタッフは 21003 を使います。3種の外観は Blockbench 5.2.2 の編集原稿から両版へ書き出しています。通常攻撃の腕振りと戦闘エフェクトはサーバー処理に同期します。BE の手持ちモデルは腕振りの進捗に合わせて動きます。弓を引くと弓身がしなり、弦の両端が弦受けに追従します。JE は3段階のモデルでつがえた矢も表示し、BE は引いた量に合わせて弓身と弦を動かします。

採集用のツルハシ6種、クワ6種、釣り竿2種は両版で立体モデルを使います。台帳の `java_gui_sprite` が有効な道具は、`bedrock/textures/items/<id>.png` を両版のインベントリとホットバーに表示します。ツルハシとクワは手持ちモデルから描画します。釣り竿は竿先から垂れる糸、赤白のウキ、リールを16 pxの専用アイコンに描き分けます。Java 版は `gui` コンテキストだけをアイコンへ切り替え、一人称は握りを基準に竿先まで見える縮尺と角度を選びます。ヴェルディアの鉄製道具はグレードごとに刃と金具を描き分けます。RPG の釣りではキャスト中も同じ竿を持ち、糸とウキは[釣りの専用演出](#釣り)で描画します。

### アイテムのツールチップ

両版で濃紺の背景と金の枠を使い、対応するパックを適用した接続では、AstralRecord のアイテム名の先頭に種類を表す絵柄を表示します。絵柄は輪郭と明暗を描き分けた16×16 pxの原画で、剣・槌・槍・弓・ワンド・スタッフや、瓶入りのポーションなど18種類です。弓の派生型は同じ絵柄を使います。

Java 版は `astralrecord:shared_item` で専用の背景と枠を選び、`astralrecord:tooltip_icons` で絵柄を描画します。統合版は `textures/ui/purpleBorder.png` を差し替えるため、この画像を参照する標準ツールチップや一部のチャット・ポーズ画面のUIにも同じ枠が適用されます。統合版の絵柄は `font/glyph_E6.png` に収録しています。両版のパックは機能の互換判定に使う `item-tooltips-version.txt` を含みます。[Minecraft Java Edition 1.21.2](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-2) · [Mojang の統合版ツールチップ画像の伸縮設定](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/textures/ui/purpleBorder.json)

原画の編集先は `assets/astralrecord/textures/font/tooltip_icons.png` です。更新時は左上の256×32 pxを統合版の256×256 pxのフォント画像に同じ位置でコピーします。既存の `U+E600` ～ `U+E611` の割り当てを維持し、両版で同じ絵柄を使います。配布用 PNG を最近傍拡大し、背景と枠を9分割で伸縮した静止画で、全18種類の輪郭とポーション名の配置を確認しました。実クライアントの描画は未確認です。

### 武器の握り位置

`java_virtual_trident: true` の武器は、台帳の `grip` に Java モデル座標の握りの中心を `[x, y, z]` で登録します。モデルは握りから先端が +Y 方向になるように作ります。ビルドは握りが形状の内側にあること、一人称・三人称それぞれの左右の手で基準に収まることを確認します。位置はモデルの 0.5 px 以内、向きは基準ベクトルとの内積 0.95 以上です。弓では引き段階にも同じ検証を行います。`item-model-grip-targets.json` では基本素材ごとに4つの姿勢を登録します。同じ基本素材を使う道具と一人称の構えを分ける場合は `astralrecord:<id>` の行を使います。Java 版の主武器41点は、待機時の握りを利き手側の下部に揃えます。一人称の剣・長杖・短杖・槌は先端を画面の外側へ、槍は先端をやや内側へ傾けます。剣は通常攻撃で刃が先に出る向き、槌は打面が前を向く構えにします。長杖と短杖は先端と焦点部が見える向きにし、弓とクロスボウは弓身と弦が見える高さに置きます。主武器41点は、三人称での握りの位置と角度を保って約12%大きく表示します。通常の弓は待機と引き3段階で同じ拡大率を使います。弓の待機時はバニラの一人称表示を基準に大きさと位置を合わせ、右手では弦が画面の外側に来る向きにします。通常の弓で待機と引き段階の姿勢が異なる場合は、同じ握り点に対する引き段階の一人称の検証基準を `draw` に登録します。クロスボウ素材のラピスの長弓は、一人称では装填中と、装填後に主手で構えたときに、それぞれ弦と矢の状態を表す専用モデルを使います。

BE の対応するモデルは `grip` ルートを `[0, 0, 0]` に置き、`q.item_slot_to_bone_name(context.item_slot)` で手にバインドします。握りのキューブはこの原点を含め、待機アニメーションで `grip` を `[0, 24, 0]` 付近に置きます。三人称ではバニラの手の前端まで、Z を最大 3 px 前に出せます。`first_person` と `third_person` はそれぞれ `context.is_first_person` と `!context.is_first_person` で1回ずつ有効にし、待機中はループさせます。縮尺を指定する場合は全軸を 0.5～2.0 に収めます。攻撃や弓を引く動作は待機姿勢の `grip` を維持します。[Mojang のヒューマノイドモデル](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/entity/humanoid.custom.geo.json)

ビルドはモデルの +Y 軸を基準に姿勢を検証します。一人称の主手では通常の腕の回転を合成し、上向き成分を 0.8 以上、奥行き成分の絶対値を 0.5 以下にします。三人称では手のローカル座標で前向きの成分を 0.8 以上にします。形状の先端と刃や弓の面は Blockbench でも確認します。[Mojang の一人称アニメーション](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/player_firstperson.animation.json)

副手用の補助装備4点も、握りの位置を基準に一人称の構えを調整します。Java 版では副手側の画面下から持ち物がつながるように左右の握りを合わせます。盾を構えると同じ形状の子モデルに切り替え、一人称で盾を上げます。統合版は一人称で主手・副手を分け、盾の一人称防御姿勢も切り替えます。盾の姿勢はバニラの握り位置を専用モデルの原点に換算したものです。統合版の通常の三人称手持ちはプレイヤーの手にバインドします。防御時に腕を上げる動作は Geyser 経由の実クライアントで未確認です。 [Mojang の盾アニメーション](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/shield.animation.json) · [Mojang のプレイヤー制御](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animation_controllers/player.animation_controllers.json)

ヴェノムの矢筒は Java 版・統合版とも通常の副手アイテムとして表示します。統合版は手にバインドした attachable で副手用の一人称姿勢を選びます。

主武器41点は、インベントリとホットバーに武器ごとの32×32 pxの専用アイコンを両版で使います。原画は `blockbench/pixel-art/<id>.pixel.json`、共用PNGは `bedrock/textures/items/<id>.png` です。Java 版は `java_gui_sprite` に従って通常表示と仮想トライデントの `gui` でこのスプライトを選びます。武器の材質、切先や鍔、弓の開口、杖頭などを描き分け、実際のスロット背景と論理16 pxで識別できることを確認します。

アクションリングの長押し設定が有効で候補が2つ以上の場合、本人の選択中の武器を仮想トライデントとして送ります。JE は `java_virtual_trident` を指定した武器の待機モデルを表示します。長押し中の一人称を待機時と分ける場合は、親モデルの形状と三人称の姿勢を継承する `<id>_virtual_use.json` で左右の専用姿勢を定義します。槍素材の武器は通常の槍の使用中にもこの専用姿勢を使います。通常時と仮想トライデントで選択中のホットバーには同じアイコンを表示します。パック形式 83 以降もオーバーレイで通常のトライデント表示を維持します。

BE では仮想トライデントで長押し入力を受け取りながら、主手の表示を `astralrecord:held_weapon_model` のモデル番号で切り替えます。番号が 0、対象武器の登録外、または副手の場合は、バニラのトライデントのモデルと使用アニメーションを使います。ビルドは `java_virtual_trident: true` の武器だけで切り替えを生成し、Geyser 用カタログに各アイテムの `held_weapon` を出力します。各武器の attachable の `first_person`、`third_person`、`attack` とその条件を再利用します。これらが使う変数は `scripts.pre_animation` で、配列の各要素につき1回の変数代入で設定します。弓の引き絵は実際に弓を使用しているときに適用されます。 [Mojang のトライデント定義](https://github.com/Mojang/bedrock-samples/blob/a3b394c507a6b11a3c6f61552e778ff5c4b89fd2/resource_pack/attachables/trident.entity.json)

新しい BE 登録はパックを API に配置した後の Proxy 再起動で有効になります。

### 2Dアイテム

素材、消耗品、採集道具、アクセサリーのピクセルアイコンを配布するときは、台帳に `render_mode: "generated"` を指定します。省略または `"3d"` は従来の立体モデルです。`generated` は `java_virtual_trident: true` や `grip` と併用できません。

入力は `assets/astralrecord/textures/item/custom/<id>.png` と `bedrock/textures/items/<id>.png` の2枚です。ビルドは Java 版でピクセルアイコンを使うモデルを生成し、統合版でアイコンをアトラスに登録します。立体モデルの Blockbench 原稿、attachable、geometry、animation は不要です。

本番素材160点、消耗品60点に32×32 pxの専用テクスチャを使います。薬は記号と液色で用途を、瓶の形で容量を表します。

統合版は Geyser 拡張1.23.0以降を使用します。ポーションは地面表示のポーションと、所持枠や手持ちに送信する紙の両方に同じアイコンを登録します。鍛冶型のアイコンは、紙に付けた `item_model` とモデル番号で照合します。既定値と異なる統合版のスタック上限は、台帳の `max_stack_size` へ1～64で指定します。

原画は [blockbench/pixel-art](blockbench/pixel-art/README.md) のパレット付きJSONで編集できます。`python scripts/generate-pixel-item-assets.py --check` で、全原画のRGBA画素とPNGの構造、配布PNGの記録済みSHA-256を検証できます。両版にPNGを保存するアイテムでは、両出力のバイト一致も確認します。

配布PNGから最近傍で16・32・64 pxの明暗背景プレビューを作り、全228点の輪郭と透過を確認しました。両アーカイブでマスターからモデル・PNGまでの参照を確認し、隔離したGeyserでプロトコル2168・2193・2225のアイテム変換を検証しました。実クライアントでの手持ちやキャスト中の描画は未確認です。

未登録の同素材を維持するため、基本素材ごとのバニラ定義を `java-item-fallbacks/` に用意します。ヘッドは形式83以降の座標変換が異なるため、`transformed_item_models/` の該当定義も使います。時計の時刻表示、プレイヤーヘッドのスキン、壁などのブロック形状を含め、fallback全体を保持します。

## ウェイストーンと施設NPC

ウェイストーンは石碑の結晶と紋章を解除済みの青緑色と未解除の赤橙色で切り替えます。ストレージは金具を付けた木箱、溶鉱炉は鋳鉄の外枠と火格子のあるモデルです。既存の配置に適用します。

Java 版は表示用 ItemDisplay の `custom_model_data.strings[0]` で `assets/astralrecord/models/world` のモデルを選びます。ビルドで `LODESTONE`、`CHEST`、`BLAST_FURNACE` のアイテム定義へ選択条件を追加し、通常のアイテムと既存の GUI モデルをフォールバックに使います。

統合版は `astralrecord:world_display` の状態値に応じて、対応する形状とテクスチャを選びます。新しいパックの公開後に対応する AstralRecordGeyserExtension を配置して Proxy を再起動し、統合版の接続をやり直します。変換の仕様は [Geyser 拡張の導入手順](../10_plugin/AstralRecordGeyserExtension/README.md) を参照してください。

石碑は所持・着用アイテムではなく、配置地点の専用3D表示です。統合版では生成マーカー付きの空TextDisplayをGeyserが `astralrecord:world_display` に変換し、`astralrecord:style` の0（未解除）/1（解除済み）で同じ形状の赤橙/青緑テクスチャを選びます。名称・解除費用の文字は別のTextDisplayで表示します。モデル対応パックがない接続では専用モデルを送らず、名称と状態色の粒子を維持します。

`python scripts/validate-world-display.py` は、両版の石碑の形状・全面UV・状態別テクスチャと描画参照を照合し、配布元資産を使った3方向の比較PNGとJSON結果を `../target/bedrock-teleporter-qa/` へ出力します。PillowとNumPyが必要です。`--java-pack <ZIP>` と `--bedrock-pack <MCPACK>` を併用すると、生成アーカイブの参照と収録資産も検証します。比較PNGはキューブとUVを使う独立したソフトウェア描画で、Minecraftクライアントの描画結果ではありません。

2026-11-11の確認では、未解除・解除済みの8パーツと全面UV・64×64テクスチャが両版で一致し、現行パックとGeyser拡張による隔離通信検証で3D識別子・状態・座標・表示倍率・非表示フラグ・消去を確認しました。パックなし・旧パック・必要資産が欠けたパックの代替表示も確認しました。実際の統合版クライアントでの描画と、本番環境への適用は未確認です。

## ボス・ダンジョン受付

ボス受付では石の祭壇に桃色の浮遊剣、ダンジョン受付では青緑の石門を表示します。表示は受付地点の向きに合わせます。スニーク案内はモデルの上に表示します。

Java 版は管理パックの互換版 `receptionDisplay: "1"` と適用成功を確認し、固定向きの ItemDisplay に専用アイテムモデルを割り当てます。統合版は Geyser の `gameplay.force-resource-packs: true` が有効で、対応パックを登録してログインした接続で Geyser Extension が専用の `astralrecord:reception_display` に変換します。対応パックがない接続では案内テキストを使います。配布時は両版のパックを公開し、対応する RPG Plugin と Geyser Extension を配置して再起動します。

ダンジョン受付は門枠を固定したまま、青緑の膜の渦を動かします。Java 版は透明な画素の抜けで奥が見え、ボス受付の剣は刃の光だけが流れます。統合版は膜と刃の発光芯に半透明を使い、ボス受付では剣だけが 3.2 秒周期でわずかに上下します。台座・門枠・当たり判定・挑戦条件は変わりません。

素材の再生成結果は `python scripts/generate-reception-animation.py --check` で照合できます（Pillow、NumPy が必要）。両版の実クライアントでの描画は未確認です。

## ビルド

### 着用防具

防具のインベントリ・装備欄・ホットバーは、`java_gui_sprite` で統合版と共用する専用アイコンを表示します。Java 版は `gui` のときに同じ PNG を選び、手持ちには `item-models.json` の3Dアイテムを使います。着用外観は追加台帳 `armor-assets.json` で管理します。各行は同じ `id` / `base_item` / `custom_model_data` と、身体部位 `slot`（`HEAD` / `CHEST` / `LEGS` / `FEET`）、`equipment_asset` を持ちます。マスターの `appearance.equipmentAsset` と同じ `astralrecord:<path>` を指定します。

- JE: `assets/astralrecord/equipment/<path>.json` の `layers.humanoid`（脚だけは `humanoid_leggings`）から、`textures/entity/equipment/<layer>/<texture>.png` を参照します。標準の立体鎧の各面に、素材に合わせた面取り、接合部の影、布の襞を描きます。
- Java 版の登録済み革の兜は、`head` コンテキストで `EQUIPPABLE` があるときに `models/item/custom/<id>_worn.json` の着用専用モデルを表示します。ビルドが立体着用に対応するモデル番号を `worn-helmet-models.json` に出力し、配布処理が同じ ZIP のマニフェストへ転記します。対応パックを適用した閲覧者への送信コピーでのみ、頭部スロットを保ったまま装備レイヤーの参照を外します。防具表示OFFでは `EQUIPPABLE` が外れ、`head` は空モデルを選びます。所持品・手持ちはそれぞれ専用アイコン・手持ち用の立体アイテムを使います。
- BE: `models/entity/armor/<id>.geo.json` と `textures/models/armor/<id>.png` を追加します。既存attachableの `geometry.worn` と `textures.armor` がこれらを参照します。着用モデルは標準防具と同じ `waist` → `body` → 頭・腕・脚の骨名と親子関係でプレイヤーの動きに追従します。この骨には明示的な `binding` を付けません。梱包時に部位別の標準オフセットアニメーションを着用側へ追加します。 [Mojang のプレイヤー防具](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/entity/player_armor.json) · [部位別オフセット](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/animations/armor.animation.json)
- 同attachableのrender controllerは、`controller.render.item_default` に `context.item_slot == 'main_hand' || context.item_slot == 'off_hand'`、`controller.render.astralrecord.armor_worn` に `context.item_slot != 'main_hand' && context.item_slot != 'off_hand'` の条件を付けます。追加した当て板、留め具、革の重ねは着用用geometryに含め、着用全体を閲覧者の設定に従って切り替えます。

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

Java 版アクションバーのバフアイコンは `assets/astralrecord/textures/buff_hud/` の 10×10 RGBA PNG から `scripts/generate-buff-hud.py` で生成します。右端に不透明または半透明の画素を置くと、字形の送り幅が 11 px に揃います。プラグインの [buff-hud-icons.json](../10_plugin/AstralRecord/src/main/resources/buff-hud-icons.json) の順番が U+EA00 からの字形番号に対応します。既存項目の順番を保持し、新しいアイコンは末尾に追加してください。`uv run --with pillow==13.0.0 python scripts/generate-buff-hud.py --check --preview <PNGパス>` で生成済みアセットとプレビューを確認できます。パックのビルドは原画と生成済みアセットの組み合わせを検査します。

通常攻撃の絵柄を変更するときは `scripts/generate-weapon-attack-assets.py` で両版の32 pxスプライトを再生成します。

## 全装備アートの編集元と確認範囲

装備アート台帳には、武器41点、防具82点、オフハンドに実表示する補助装備4点、採集道具14点の3Dモデルと、スロットで表示するアクセサリ12点の2Dアイコンを登録しています。

Minecraftの角形と粗い画素の材質分けを基準に、ノクスは簡素な初期装備、上位は素材の分離、縁、留め具、各系統の意匠を増やしています。防具のインベントリ・手持ち用の立体と、着用用の標準リグ・原画は別の編集データです。頭装備22点はJavaの着用専用原稿を基準に、両版で王冠・枝冠・帽子のつば・星飾りなどの固有造形と顔の開口を揃えています。統合版の頭装備は256×128の着用アトラスを使い、既存のJava着用テクセルを再配置しています。胴・脚・足は従来の素材と部位別の立体表現を維持します。

- `blockbench/items/`: native Blockbench 5.2.2で書き出した両版の編集元。防具は `<id>_worn.bedrock.bbmodel`、Java版の立体着用兜は `<id>_worn.java.bbmodel`、弓は3段階のJava draw原稿も保持します。
- `blockbench/equipment-drafts/`: 新規3D124点の個別cuboid・素材swatch設計原稿。
- `blockbench/armor-pixels/`: 着用原画のpalette・pixel-grid記録。原画PNGはJE/BE着用テクスチャと着用Blockbench原稿にも保存しています。
- `blockbench/pixel-art/`: 主武器・防具のスロットアイコン、2Dアクセサリ12点、その他の2Dアイテムの再生成可能な原画。
- `blockbench/equipment-source-manifest.json`: 全153点のマスター対応と編集元・配布元アセットのSHA-256。

編集元台帳のSHAは、`.json` と `.bbmodel` のCRLFをLFへ正規化して計算します。その他の内容は変換せず、PNG等のバイナリは元のバイト列を使います。Gitのcheckout改行設定が異なっても、同じ編集内容を同じハッシュで追跡できます。

制作は原則3〜4点ずつ分担し、native前後・斜め、実32pxアイコン、持ち点、全周UV、着用人形の顔開口を確認しています。弓はJava draw3段階とBE draw4状態・attack5状態を確認しました。この一括制作では、モデルのプレビューに加えて、パックの参照とビルドの決定性を検査しました。

Java 版の立体装備141点は、握りの中心と左右の一人称表示を配布モデルで確認しました。見切れや表示の大きさを調整した138点は、握り点を基準に移動・拡縮し、編集原稿と配布モデルの表示設定を同期しています。確認の基準は標準 HUD、画角70°です。

ノクス武器3種は、隔離した Java Edition 1.21.11 で手持ちと弓の引き3段階を撮影しました。統合版は配布用 MCPACK のモデル・テクスチャ・アニメーションを使い、腕を含むシーンの手持ち、通常攻撃と弓の引き動作をオフスクリーンで描画しました。統合版の実クライアント描画は未確認です。

## サーバー配布

1. 初回は [Geyser Extension の導入手順](../10_plugin/AstralRecordGeyserExtension/README.md) に従い、Velocity の拡張を更新します。起動時と以後60秒ごとにAPIのBedrockパックを確認し、新しい接続へ配布します。
2. API・Web・Plugin を有効にした設定で [01-deploy-debug.bat](../60_tool/01-deploy-debug/README.md) を実行します。API の配置物に両パックと最新ハッシュを含めます。
3. Java 版はゲームサーバーから公開WebのダウンロードURLを受け取ります。配布先を変える場合は RPG Plugin の `resourcePack.managed.publicBaseUrl` を設定します。統合版は Geyser の接続時にパックを受け取ります。更新したパックの適用は再接続時です。Java ZIP は公開 Web の受信上限 250 MiB 以内に収め、配置後に公開 URL から取得した ZIP の SHA-1 がマニフェストと一致することを確認します。

アクションリングやスキル表示の更新時は、対応する両版パックを API へ公開してから RPG Plugin を更新します。統合版のアイテム登録に更新を反映するには Geyser プロキシを再起動し、クライアントを再接続します。旧パックでは新規のスキル表示用モデルが紙の見た目になります。

RPG Plugin の `resourcePack.bedrockNamePrefixes` は、Velocity の Floodgate の接続名接頭辞と一致させます。現行環境は `.` です。

## 挑戦開始カウントダウン v1

`challenge-countdown-version.txt=1` はボス／ダンジョン開始の画像・音声が一式揃うことを表す。Java manifest は `challengeCountdown: "1"`、Bedrock は選択済みパックのマーカーと22フレーム・5短音を検証する。従来の gameplay-sounds v1 だけでは対応扱いしない。

`python scripts/generate-challenge-countdown.py` は Pillow・numpy・imageio-ffmpeg を使い、同梱のライセンス済み NotoSansJP atlasから画像・名前fontを、数式合成からOgg短音を再生成する。新たなOSフォントや音源素材は不要。リングは10秒を維持し、3→1を強調。金／青緑で名前も色付けし上部へ表示する。

Bedrock Title factory はGeyserの先頭 `§r` を除いた `arcd1:b:0..10` / `arcd1:d:0..10` の完全一致だけを画像表示し、その他のTitleはバニラ定義をそのまま継承する。専用音は `astralrecord:challenge.countdown.{tick,three,two,one,start}`。各秒をサーバーから独立送信し、中断時は停止する。

## Bedrock reward and world-display compatibility

Combat reward text, time, and item rows are rendered together as a Geyser nametag.
The independent Bedrock decorative frame is disabled; Java reward frames are unchanged.
Animated reception packs are admitted only when both 32-frame texture sequences are complete.
Older complete static reception packs remain supported. Without a verified mandatory
pack, the Bedrock gate, reception sword, and waystone use non-interactive vanilla
markers instead of disappearing. To avoid double markers, update Paper and the
Geyser extension together; use the matching pack and reconnect.
