# Resourcepack Item Model Generation Prompt

Use this prompt when creating a Minecraft Java resource pack item model and texture from an AstralRecord DB item definition.

```text
対象プロジェクトは resourcepack です。

40_filebase 配下の指定アイテム定義を読み、AstralRecord の Minecraft Java 1.21.11 用リソースパックに、対象アイテムの見た目を追加してください。

対象アイテム:
- Filebase YAML path: <例: E:/AstralRecord-Workspace/40_filebase/10.features.item/equipment/v1.sample_sword.yml>
- item id: <例: sample_sword>

作業方針:
1. DB YAML から `id`, `category`, `name`, `icon`, `rarity`, `customModelData`, `equipment.tag`, `equipment.slot`, `equipment.handType`, lore や stats など、見た目に関係する情報を確認する。
2. `icon` の Material を Java 側の表示ベースアイテムとして扱う。例: `IRON_SWORD` なら `minecraft:iron_sword`。
3. `customModelData` はこのプロジェクトでは Paper の `ItemMeta#setItemModel(new NamespacedKey("astralrecord", "item/" + customModelData))` により、`astralrecord:item/<customModelData>` として参照される点に注意する。
4. そのため、最低限以下を追加する。
   - `assets/astralrecord/textures/item/<item_id>.png`
   - `assets/astralrecord/models/item/<item_id>.json`
   - `assets/astralrecord/items/item/<customModelData>.json`
5. バニラ `/give` 検証用に、必要なら以下も追加/更新する。
   - `assets/minecraft/items/<base_item>.json`
   - `assets/minecraft/models/item/<base_item>.json` または `<base_item>_base.json`
6. 既存の `sample_sword` と同じ構成・命名・JSON スタイルに合わせる。
7. 画像生成が必要な場合は imagegen を使う。生成画像は必ず workspace 内の `assets/astralrecord/textures/item/` に保存する。
8. 画像は Minecraft item icon として読みやすいよう、64x64 PNG、透明背景、斜め構図、強いシルエット、過度な細部なしにする。
9. モデルは基本的に `minecraft:item/handheld` または `minecraft:item/generated` を親にする。武器は `handheld`、素材/消耗品/通貨は `generated` を優先する。
10. `scripts/build-resourcepack.ps1` で zip を再生成し、zip 内パスが `/` 区切りであることを確認する。
11. GDLauncher インスタンスへ反映する場合は、生成済み zip を以下へコピーする。
    `C:/Users/kaede/AppData/Roaming/gdlauncher_carbon/data/instances/Instance 1.21.11/instance/resourcepacks/AstralRecordResourcePack.zip`

画像生成プロンプト方針:
- Asset type: Minecraft Java resource pack item texture
- Style: polished pixel-art inspired fantasy RPG item icon
- Composition: centered, diagonal when weapon/tool, clear silhouette, transparent background
- Constraints: no text, no watermark, no UI frame, no cast shadow, readable at 16x16/32x32/64x64
- Reflect DB semantics: rarity, equipment tag, role, material, element, lore theme, stats theme

検証コマンド例:
```mcfunction
/give @p minecraft:<base_item>[minecraft:item_model="astralrecord:item/<customModelData>"] 1
```

プラグイン側確認:
```mcfunction
/item get <item_id>
```

完了条件:
- JSON が valid
- texture PNG が存在し RGBA/透明背景
- model から texture 参照が解決できる
- item definition から model 参照が解決できる
- zip 再生成済み
- 必要なら GDLauncher resourcepacks にコピー済み
```
