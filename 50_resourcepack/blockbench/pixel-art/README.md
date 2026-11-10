# 2Dアイテムの編集元

このフォルダの `<id>.pixel.json` は、透過ピクセルアイコンの編集元です。Blockbenchの立体プロジェクトとは形式が異なります。`palette` は1文字からRGBA色への対応、`pixels` は上から下への各行です。追加の `intent`、`inspiration`、`native_pixels` などは制作者の意図と元のグリッドを残す情報です。

Minecraftの鉱物・チェーン・護符・時計などを参考に、太い輪郭、少数色、明確なピクセルのまとまりで描きます。ノクスは身近な材質と簡素な形、上位品は刻印・核・留め具の層を増やします。実写の宝飾品、アンチエイリアス、細かすぎる写実描画は使いません。

`palette` / `pixels` を編集した後は、`50_resourcepack` で次を実行します。IDを省略すると全編集元を生成します。

```powershell
python scripts/generate-pixel-item-assets.py nox_charm
python scripts/generate-pixel-item-assets.py --check
```

2Dアイテムの生成先は `assets/astralrecord/textures/item/custom/<id>.png` と `bedrock/textures/items/<id>.png` です。同じバイト列を両版へ保存し、編集元の `png_sha256` を更新します。`native_pixels` を含む編集元で元グリッドを変更した場合は、その補助情報も編集内容に揃えてください。

2Dアイテムは配布台帳の `render_mode: "generated"` を使います。Java版のアイテムモデルと統合版のアイコン登録はビルド時に生成します。

`item-models.json` で `java_gui_sprite` を指定した立体装備のスロット原画は `bedrock/textures/items/<id>.png` に出力します。Java版のインベントリとホットバーにも、ビルド時に同じPNGを組み込みます。防具と釣り竿は16×16で描きます。主武器は32×32で描き、論理16 pxのスロットで武器種と個体を識別できることを確かめます。手持ちの立体アトラスと着用原画はそれぞれ `blockbench/items/` と `blockbench/armor-pixels/` から管理します。実クライアントの装備表示確認は、ソースとPNGの一致検証とは分けて記録します。 [武器アイコンの制作手順](../../../.codex/skills/astralrecord-item-model-author/references/weapon-slot-icons.md)
