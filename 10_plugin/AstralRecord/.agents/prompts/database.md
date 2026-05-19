# Plugin Database Prompt

## 読むタイミング

- Plugin 側の DB アクセスコードを追加・変更するとき
- DB 契約に依存する機能を作るとき
- DB スキーマ変更を伴う作業をするとき
- file 系マスタデータに依存する機能を作るとき

## 確認対象

- repository の入出力モデル
- API 契約
- `E:\AstralRecord-Workspace\40_database\` 配下の SQL Server DB / テーブル定義
- `E:\AstralRecord-Workspace\50_filebase\` 配下の file 系マスタデータと YAML スキーマ
- API 側の `20_api/AstralRecordApi/docs/api/`

## 必須ルール

- DB スキーマ前提のコードを書く前に、`40_database` の定義書と実装が一致しているか確認する。
- file マスタ前提のコードを書く前に、`50_filebase` の YAML とスキーマ定義が一致しているか確認する。
- テーブルやカラムの変更を伴う場合は `40_database` 側の更新漏れを疑う。
- file マスタの構造変更を伴う場合は `50_filebase` 側の更新漏れを疑う。
- API と Plugin の契約変更は片側だけで終わらせない。

## 非推奨

- 古い契約を前提に repository や DTO を増やすこと
- Database / Filebase 側の定義を確認せずに DB 名称や YAML パスを決め打ちすること
