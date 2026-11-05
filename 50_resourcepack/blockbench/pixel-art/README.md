# アクセサリーの編集元

このフォルダの `<id>.pixel.json` は、32×32の透過ピクセルアイコンの編集元です。Blockbenchの立体プロジェクトとは形式が異なります。`palette` は1文字からRGBA色への対応、`pixels` は上から下への各行です。追加の `intent`、`inspiration`、`native_pixels` などは制作者の意図と元のグリッドを残す情報です。

Minecraftの鉱物・チェーン・護符・時計などを参考に、太い輪郭、少数色、明確なピクセルのまとまりで描きます。ノクスは身近な材質と簡素な形、上位品は刻印・核・留め具の層を増やします。実写の宝飾品、アンチエイリアス、細かすぎる写実描画は使いません。

`palette` / `pixels` を編集した後は、`50_resourcepack` で次を実行します。IDを省略すると全編集元を生成します。

```powershell
python scripts/generate-pixel-item-assets.py nox_charm
python scripts/generate-pixel-item-assets.py --check
```

生成先は `assets/astralrecord/textures/item/custom/<id>.png` と `bedrock/textures/items/<id>.png` です。同じバイト列を両版へ保存し、編集元の `png_sha256` を更新します。`native_pixels` を含む編集元で元グリッドを変更した場合は、その補助情報も編集内容に揃えてください。

配布台帳は `render_mode: "generated"` を使います。JEのgenerated親モデルとBEのアイコン登録はビルド時に生成し、ここへ3D geometryやattachableは置きません。実クライアントの装備表示確認は、ソースとPNGの一致検証とは分けて記録します。
