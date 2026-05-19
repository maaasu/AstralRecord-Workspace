# AstralRecord リソースパック指示

このディレクトリは、AstralRecord サーバで配布するリソースパックを管理します。

## 必ず確認する情報

アセット、モデル、メタデータを編集する前に、存在する場合は以下のファイルを確認してください。

- `resourcepack.config.json`
- `README.md`
- `../10_plugin/AstralRecord/pom.xml`
- `../10_plugin/AstralRecord/src/main/resources/plugin.yml`
- `../50_filebase/config.yml`

参照先の管理には `resourcepack.config.json` を使います。基本はこのディレクトリからの相対パスを優先してください。相対パスで解決できない環境では、`resourcepack.config.json` の `absolutePathOverrides` を確認してください。

## Minecraft バージョン

対象 Minecraft バージョンは `../10_plugin/AstralRecord/pom.xml` から判断します。

現在確認済みの値:

- `paper-api`: `1.21.11-R0.1-SNAPSHOT`
- Minecraft Java Edition: `1.21.11`
- Resource pack `pack_format`: `75`

`pom.xml` のバージョンが変わった場合は、`pack.mcmeta` の `pack_format` も更新が必要か確認してください。

## リソースパックの範囲

- 独自アセットには `astralrecord` 名前空間を使う。
- `minecraft` 名前空間は、バニラアセットを意図的に上書きする場合だけ使う。
- ファイル名とディレクトリ名は小文字で統一する。
- モデル識別子、テクスチャ名、言語キーは、プラグインやDB定義のアイテム識別子と対応させる。
- ユーザーが明示的に求めた場合、または作業に必須の場合を除き、大きなバイナリアセットは追加しない。

## 検証

構造を変更した後は、以下を確認してください。

- `pack.mcmeta` が有効なJSONであること。
- モデルファイルが有効なJSONであること。
- モデルから参照しているテクスチャが存在すること。
- `scripts/build-resourcepack.ps1` で `dist/AstralRecordResourcePack.zip` を作成できること。

## GitHub Copilot

`.github/copilot-instructions.md` は参照用のブリッジファイルです。リソースパックの指示を変更する場合は、この `AGENTS.md` を更新してください。
