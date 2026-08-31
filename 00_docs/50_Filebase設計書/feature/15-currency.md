# Currency 設計

## 役割

Currency は、交換、通行、討伐証明などに使用する item 形式の通貨・証です。

## 設計方針

- 入手元と主な消費先を少なくとも1つずつ成立させます。
- 同じ用途の currency を別名で重複させません。
- 期限を設ける場合、失効日時と交換終了の運用を同時に確認します。
- 通常 item と異なる管理挙動は Plugin の実装済み範囲だけを使用します。

ストレージクラウドアクセストークンは、クラウドストレージへのアクセスを表すシステム用の証として `astrald_shop` から300アストラルドで入手します。

## progression

最初に標準入手できる段階を基準にします。複数進行帯で使う場合も値を都度変更しません。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\currency\docs.currency.YAMLスキーマ定義.md`
