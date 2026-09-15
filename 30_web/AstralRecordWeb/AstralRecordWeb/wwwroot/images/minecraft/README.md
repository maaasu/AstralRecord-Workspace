# Minecraft Material icons

このディレクトリは、Webスキルツリーで使うBukkit MaterialのPNGアイコンです。ファイル名は小文字のMinecraft Material IDです。

## 対象と取得日

- 対象調査日: 2026-09-16
- 取得対象: `40_filebase/35.features.skilltree/nodes/*.json` にある73種類の `icon`
- 配置済み: 73種類

## 出典

55種類は、Skill Tree Editorの既存キャッシュから2026-09-16にそのままコピーしました。Editorは [MC Icons API](https://mc-icons.com/api) の次のPNG取得経路を使用します。

```text
https://mc-icons.com/download/{material}/thumb
```

キャッシュに無かった次の17種類は、2026-09-16に同じURLからPNGとして取得しました。

```text
comparator, conduit, copper_grate, globe_banner_pattern, iron_axe,
iron_trapdoor, lead, music_disc_tears, netherite_axe, netherite_block,
netherite_chestplate, reinforced_deepslate, respawn_anchor, sea_lantern,
totem_of_undying, warden_spawn_egg, waxed_oxidized_copper_lantern
```

`chain` はMC Iconsの同経路が404でした。Minecraft Java Edition 1.21.9でChain blockがIron Chainへ改名されたことを[公式リリース記事](https://www.minecraft.net/nl-nl/article/minecraft-java-edition-1-21-9)で確認し、2026-09-16に取得した同じMaterialの`iron_chain`画像を`chain.png`として置いています。

```text
https://mc-icons.com/download/iron_chain/thumb
```

既存キャッシュ内ファイルの元のダウンロード日時は記録されていません。このディレクトリへコピーした日は上記のとおりです。画像の変換、合成、別Materialへの置換は行っていません。`chain.png`だけは上記の旧Material名との互換aliasです。
