# Mob render images

このディレクトリは、本人専用討伐モブ図鑑の画像出典記録です。画像は同梱せず、固定allowlistから Minecraft Wiki の各Mob infobox画像を参照します。

## 作成方法と出典

- 作成日: 2026-09-16
- 出典: [Minecraft Wiki](https://minecraft.wiki/) の各Mob記事のinfobox画像（画像確認日: 2026-09-16）。
- 同Wikiの各記事フッターはコンテンツを [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) と示しています。画像の個別ライセンス・Mojang著作権は画像ページの表記を優先します。
- Mob画像は Mojang Studios / Microsoft の著作物に由来します。サイト上の利用は [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines) に従います。

`mob-viewer.mjs` は本番enemy/bossで確認した `EntityType` の固定URL allowlistだけを解決します。URLは外部入力から作らず、`img` は `referrerpolicy=no-referrer` を指定します。未対応型または読み込み失敗時には画像なしラベルへ戻します。
