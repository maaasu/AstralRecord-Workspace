# Mob render images

このディレクトリは、本人専用討伐モブ図鑑に表示する Minecraft Java Edition のバニラMob外観PNGです。

## 作成方法と出典

- 作成日: 2026-09-16
- 作成元: ローカルの正規 Minecraft Java Edition 26.1 クライアントデータ
- レンダリング: [minecraft-library/asset-renderer](https://github.com/minecraft-library/asset-renderer) の `entityRender3D`（Apache License 2.0）。このライブラリはクライアントJAR由来アセットを同梱せず、ローカルでレンダリングします。
- Mob画像は Mojang Studios / Microsoft の著作物に由来します。サイト上の利用は [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines) に従います。

`mob-viewer.mjs` は本番enemy/bossで確認した `EntityType` の固定ローカルパスallowlistだけを解決します。外部接続は発生しません。未対応型または読み込み失敗時には画像なしラベルへ戻します。
