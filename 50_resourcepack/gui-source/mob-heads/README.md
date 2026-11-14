# 冒険記録の頭アイコン

`heads.json` は35種類の頭の描画入力です。バニラの頭アイテムがある種はPluginがそのアイテムを使うため、ここには追加しません。追加分は実形状・UV・テクスチャから描画し、両版で同じ32×32 RGBA PNGを使います。`gui-icons.json` の `mob_head_*` / `minecraft:paper` / CMD 910169〜910203 に対応します。

通常モブは [Mojang Bedrock Samples v1.21.130.3](https://github.com/Mojang/bedrock-samples/tree/84aa19df535fcf6d044f28bf76f236029bd8407a) のモデルを使用しています。元の形状の頭と子ボーンを抽出し、親ボーンの変換だけを残しました。旧形式の継承形状は同名ボーンの立方体を結合しています。各原モデルのURL・SHA-256、使用する原テクスチャのURL・SHA-256は `provenance.json` に保存しています。取得日: 2026-11-14。利用条件は原文の `LICENSE-Mojang.md` を参照してください。通常のパックビルドや再生成にネット接続は不要です。

顔の一部が別ボーンにあるウサギは耳・鼻も抽出します。アルマジロは目がある殻の前3単位を含め、ラクダは首の下部を除きます。UVの画素密度は維持します。スライムは顔の内層と透明な外殻を重ねます。色違いは茶色のムーシュルーム、緑・灰色のオウム、茶色のウサギです。年齢・個体倍率・装備はアイコンに適用しません。

サンバードとセレネアは既存の `bedrock/models/entity` の `head` ボーン、ニクセロンは顔の核・外殻・目・冠を使います。元モデルのUVやテクスチャの変更は再生成時に反映されます。

```text
uv run --with pillow==13.0.0 --with numpy==2.3.5 python scripts/generate-mob-head-icons.py
uv run --with pillow==13.0.0 --with numpy==2.3.5 python scripts/generate-mob-head-icons.py --check --preview <確認用PNG>
```

リソースパックルートで実行します。レンダラーは直交投影、最近傍のテクスチャ読み取り、深度判定、透過合成を使います。各頭を28px以内に収め、実寸16pxと拡大図を暗い背景・通常スロット背景で比較できます。`--check` は原テクスチャのハッシュ、全35種の配布PNGとのバイト一致、GUI台帳の対応を検査します。配布用モデルを描画した静的プレビューであり、Minecraftのゲーム画面ではありません。
