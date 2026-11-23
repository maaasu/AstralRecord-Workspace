# GUI コントロール原画

Java版と統合版へ同じ32×32 RGBA PNGを出力します。メインメニューの機能アイコン13点は32pxで描き、16px相当でも太い輪郭と余白で判別できるようにします。金属、革、羊皮紙などの材質は、大きな明暗の面で描き分けます。それ以外の共通原画は16×16の設計画素を最近傍で2倍にします。ページ操作や確認ボタンは明るい金縁で揃えます。ダミーと仕切りは透過させ、無効・処理中などの状態は暗い凹面で区別します。

共通ボタンと状態表示の石板は [Kenney UI Pack - Pixel Adventure](https://kenney.nl/assets/ui-pack-pixel-adventure) 2.0 の CC0 素材をベースにしています。選択したのは `Tiles/Small tiles/Thin outline/tile_0058.png` の1枚です。石板の形を使い、操作アイコンの縁と状態表示の色を分けました。機能・操作記号はAstralRecord用の独自原画です。著者ロゴやMinecraftのテクスチャ画素は取り込んでいません。

元PNGと同梱の `License.txt` は `kenney-pixel-adventure/` に保存しています。`stone-tile-pixels.json` は元PNGを非補間で読み取った設計用画素表です。原アーカイブと選択ファイルのSHA-256、取得日、使用範囲、改変内容は `provenance.json` に記録しています。CC0では改変・再配布・商用利用が認められ、クレジットは必須ではありません。両配布パックの `NOTICE-GUI.txt` に任意の出典表示を収録します。

Minecraft用の配布パックでは [Quartz UI](https://github.com/Azerxim/MC-Quartz-UI) も比較しました。同リポジトリのMITライセンスは改変・再配布を許可し、著作権表示と許諾文の保持を要求します。全体を白くする意匠は、既存の戦闘HUDと装備アートの配色に合わせる今回の共通部品へは採用せず、素材のコピーも行っていません。

## 再生成と確認

Python標準ライブラリだけで再生成できます。フォント、外部画像処理パッケージ、ネット接続は不要です。

```text
python scripts/generate-gui-assets.py
python scripts/generate-gui-assets.py --check
python scripts/generate-gui-assets.py --check --preview <作業用のPNGパス>
```

メインメニューの原画は `scripts/gui_menu_icon_art.py`、その他のパレットと記号の画素表は `scripts/generate-gui-assets.py` にあります。`--check` は既存PNGと生成結果をバイト単位で比較し、差分や欠落があれば失敗します。GUI台帳・クリック判定・スロット配置は変更しません。

プレビューの各セルには16px、32px、64pxと明背景上の16pxを表示します。順番は隣のJSONに保存します。原寸では矢印の方向、戻ると前ページの違い、確認と増加、取消と減少、施錠と未設定、金色と青緑の隣接色の識別を確認します。減少は横棒、取消は斜線入りの円として色以外でも区別しています。

`frame` と `panel` は内容を持たない透過ダミー用です。商品・装備・報酬・プレイヤースキン等の内容アイコンを共通記号に置換しません。画面全体の背景、Javaのタイトル用フォント、統合版ネイティブフォームは別の描画経路です。原画プレビューの確認は実クライアントでの視認性・操作確認を代替しません。

## コンテナと所持品の背景

カルトグラフの部屋・通路・方角は `scripts/generate-cartograph-gui.py` が別の16px設計画素から両版共通の32px PNGを出力します。`gui-icons.json` に登録した73絵柄を `--check` で両版とも照合できます。`--preview <PNGパス>` は実際の配布PNGを並べ、Pillowが必要です。通常の共通コントロールの生成対象とは分けています。

紺と金の枠、銘板、天体の装飾は AstralRecord の原画です。`generate-gui-panels.py` は Java のメニュー背景と統合版の所持品アセットを、`generate-java-inventory.py` は Java の所持品背景とレシピボタンを生成します。

Java のコンテナ用画像は、公式クライアントの背景をもとに、枠、ラベル周辺、スロットを描き直しています。スロットの座標や各ウィジェットが読む画像の範囲は、公式クライアントのアトラスに合わせています。対象は 1.21.11、26.1、26.2、26.3 です。各クライアント JAR は [Mojang のバージョンマニフェスト](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) から該当バージョンの `downloads.client` を参照して取得し、同じディレクトリに `<バージョン>.jar` として置きます。生成スクリプトは SHA-1 と、全4版で生成画像が一致することを確認します。

```text
python scripts/generate-gui-panels.py --check
python scripts/generate-java-inventory.py --check
uv run --with pillow==13.0.0 python scripts/generate-java-containers.py --clients-dir <公式JARのディレクトリ> --check --preview <確認用PNG>
```

編集した原画を出力するときは `--check` を外します。コンテナの生成結果は `java-gui-native` に置きます。通常の配布パックビルドは、リポジトリにある生成済み画像を使います。

統合版の画面別設定は [Mojang の Bedrock サンプル](https://github.com/Mojang/bedrock-samples/tree/v1.26.60.5/resource_pack/ui) の画面階層に沿って、Classic と Pocket のコンテナに背景を指定します。元のスロット、タブ、閉じる操作は既存の画面定義から読み込みます。

## チャプター画面の文字

第1章の固定ラベルは、Java版1.21.11の標準日本語フォントの二値ビットマップです。
ASCII数字の「1」と日本語の字形・字送りを `chapter-glyphs.json` に記録し、
ゲームのGUI 1pxに対して原画2pxで描きます。通常のパネル再生成に外部フォントは不要です。

ゲーム本体から字形を再抽出する場合は、Pillow、1.21.11の公式クライアントJAR、および
`indexes/29.json` と `objects/` を含むMinecraftのアセットディレクトリを使用します。
`50_resourcepack` ディレクトリから、次のコマンドを実行します。

```text
python scripts/generate-gui-panels.py --chapter-only --refresh-chapter-font --client-jar <1.21.11.jar> --assets-dir <assets>
python scripts/generate-gui-panels.py --chapter-only --check
```

1行目に `--check` を加えると書き込みを行わずに実際のゲーム字形と照合できます。
