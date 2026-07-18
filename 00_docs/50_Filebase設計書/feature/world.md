# World 設計

## 役割

World は、プレイヤーの移動先、環境、配置コンテンツ、接続先を束ねる単位です。

## 設計方針

- world の用途、安全性、入退場条件、接続先を定めます。
- 個別ワールドの景観、固有名詞、敵・素材・装備の具体案は本共通設計へ記載しません。
- mob、gathering、spawner、quest などの実在する参照だけを接続します。
- world YAML だけで手動建築やランタイム配置を完結したものとみなしません。

## progression

標準的に初めて到達できる段階を記載します。world 内に複数進行帯がある場合も最初の到達段階を使用し、個別コンテンツはそれぞれの progression を保持します。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\60.features.world\world.YAMLスキーマ定義.md`
