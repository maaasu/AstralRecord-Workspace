# GUI コントロール原画

Minecraft の16pxアイコンと並べても判別できるよう、16×16の設計画素を最近傍で2倍にした32×32 RGBA PNGを使います。Java版と統合版へ同じバイト列を出力します。操作アイコンには明るい金縁と濃い青緑の面を使います。ダミー・仕切り・操作できない状態は金縁のない暗い凹面で区別します。

ボタンの石板は [Kenney UI Pack - Pixel Adventure](https://kenney.nl/assets/ui-pack-pixel-adventure) 2.0 の CC0 素材をベースにしています。選択したのは `Tiles/Small tiles/Thin outline/tile_0058.png` の1枚です。石板の形を使い、操作アイコンの縁と状態表示の色を分けました。上に載せる全31種類の機能・操作記号はAstralRecord用の独自原画です。著者ロゴやMinecraftのテクスチャ画素は取り込んでいません。

元PNGと同梱の `License.txt` は `kenney-pixel-adventure/` に保存しています。`stone-tile-pixels.json` は元PNGを非補間で読み取った設計用画素表です。原アーカイブと選択ファイルのSHA-256、取得日、使用範囲、改変内容は `provenance.json` に記録しています。CC0では改変・再配布・商用利用が認められ、クレジットは必須ではありません。両配布パックの `NOTICE-GUI.txt` に任意の出典表示を収録します。

Minecraft用の配布パックでは [Quartz UI](https://github.com/Azerxim/MC-Quartz-UI) も比較しました。同リポジトリのMITライセンスは改変・再配布を許可し、著作権表示と許諾文の保持を要求します。全体を白くする意匠は、既存の戦闘HUDと装備アートの配色に合わせる今回の共通部品へは採用せず、素材のコピーも行っていません。

## 再生成と確認

Python標準ライブラリだけで再生成できます。フォント、外部画像処理パッケージ、ネット接続は不要です。

```text
python scripts/generate-gui-assets.py
python scripts/generate-gui-assets.py --check
python scripts/generate-gui-assets.py --check --preview <作業用のPNGパス>
```

編集対象は `scripts/generate-gui-assets.py` のパレットと記号の画素表です。`--check` は既存PNGと生成結果をバイト単位で比較し、差分や欠落があれば失敗します。GUI台帳・クリック判定・スロット配置は変更しません。

プレビューの各セルには16px、32px、64pxと明背景上の16pxを表示します。順番は隣のJSONに保存します。原寸では矢印の方向、戻ると前ページの違い、確認と増加、取消と減少、施錠と未設定、金色と青緑の隣接色の識別を確認します。減少は横棒、取消は斜線入りの円として色以外でも区別しています。

`frame` と `panel` は内容を持たない区切り用です。商品・装備・報酬・プレイヤースキン等の内容アイコンを共通記号に置換しません。画面全体の背景、Javaのタイトル用フォント、統合版ネイティブフォームは別の描画経路です。原画プレビューの確認は実クライアントでの視認性・操作確認を代替しません。
