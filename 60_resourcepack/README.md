# AstralRecord リソースパック

AstralRecord サーバで配布する Minecraft Java Edition 向けリソースパックの雛形です。

## 対象バージョン

- Minecraft Java Edition: 1.21.11
- Resource pack format: 75
- サーバ/プラグイン側バージョン情報の参照元: `../10_plugin/AstralRecord/pom.xml`

Minecraft の対象バージョンは、`../10_plugin/AstralRecord/pom.xml` に定義されている `io.papermc.paper:paper-api` のバージョンから判断します。

## ディレクトリ構成

```text
60_resourcepack/
  pack.mcmeta
  assets/
    astralrecord/
      lang/
      models/
        item/
      textures/
        item/
      sounds/
    minecraft/
      models/
      textures/
```

独自アセットは原則として `astralrecord` 名前空間に配置します。`minecraft` 名前空間は、バニラアセットを意図的に上書きする場合だけ使用してください。

## 参照情報

このリソースパックを変更する前に、まず `resourcepack.config.json` を確認してください。環境差に強い相対パスの参照先と、必要に応じてローカル環境用の絶対パス上書きを定義しています。

主な参照先:

- `../10_plugin/AstralRecord/pom.xml`: Minecraft/Paper API バージョン。
- `../10_plugin/AstralRecord/src/main/resources`: `plugin.yml` や設定ファイルなどのプラグインリソース。
- `../50_filebase`: アイテム、装備、スキルなど、アセットと対応する file 系マスタデータ定義。

## ビルド

このディレクトリから実行します。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-resourcepack.ps1
```

生成先は `dist/AstralRecordResourcePack.zip` です。

## アセット作成ルール

- アセットのパスは小文字で統一する。
- テクスチャは `.png`、モデルは `.json` を基本とする。
- 生成されたzipは、リリース運用で必要な場合を除きコミットしない。
- カスタムモデル名、テクスチャ名、言語キーは、プラグインやDB定義のアイテム識別子と対応させる。
