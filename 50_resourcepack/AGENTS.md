# AstralRecord リソースパック指示

このディレクトリは Java Edition と Bedrock Edition のダメージ数値、アクションリング、アイテムモデルを管理します。配布対象のアイテムは `item-models.json` に登録します。旧アイテム・バニラ GUI・音声アセットは保管用です。ユーザーの明示指示がある場合に編集・配布します。

## 必ず確認する情報

アセット、モデル、メタデータを編集する前に、存在する場合は以下のファイルを確認してください。

- `resourcepack.config.json`
- `README.md`
- `../10_plugin/AstralRecord/pom.xml`
- `../10_plugin/AstralRecord/src/main/resources/plugin.yml`
- `../40_filebase/config.yml`

参照先の管理には `resourcepack.config.json` を使います。基本はこのディレクトリからの相対パスを優先してください。相対パスで解決できない環境では、`resourcepack.config.json` の `absolutePathOverrides` を確認してください。

## Minecraft バージョン

対象 Minecraft バージョンは `../10_plugin/AstralRecord/pom.xml` から判断します。

現在確認済みの値:

- `paper-api`: `1.21.11-R0.1-SNAPSHOT`
- Minecraft Java Edition: `1.21.11`
- Java リソースパック形式: `75.0` から `97.1`（Java `26.3` のクライアント）

`pom.xml` のバージョンが変わった場合は、`pack.mcmeta` の `min_format` と `max_format` も更新が必要か確認してください。

## リソースパックの範囲

- 独自アセットには `astralrecord` 名前空間を使う。
- `minecraft` 名前空間は、バニラアセットを意図的に上書きする場合だけ使う。
- ファイル名とディレクトリ名は小文字で統一する。
- フォント識別子、クライアントエンティティ識別子、プロパティは、プラグインと Geyser 拡張の定義に合わせる。
- ユーザーが明示的に求めた場合、または作業に必要な場合を除き、大きなバイナリアセットは追加しない。

## 検証

構造を変更した後は、以下を確認してください。

- `pack.mcmeta` が有効な JSON であること。
- モデルファイルが有効な JSON であること。
- マスターとアイテム台帳の番号・素材が一致し、両版のモデル、テクスチャ、アニメーション参照が解決すること。
- `scripts/build-resourcepack.ps1` で `dist/AstralRecordResourcePack.zip` と `dist/AstralRecordResourcePack.mcpack` を作成できること。両アーカイブに旧アセットが含まれず、同じ入力で再生成した SHA-256 が一致すること。

## GitHub Copilot

`.github/copilot-instructions.md` は参照用のブリッジファイルです。リソースパックの指示を変更する場合は、この `AGENTS.md` を更新してください。
