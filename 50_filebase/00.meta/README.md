# 00.meta（運用メタ情報）

このフォルダは、Database配下のデータ管理を「迷わず・混ぜず・増やしても崩れない」ようにするための共通ルール置き場です。

## 目的
- item / class / quest など「機能追加の単位（features）」と、
  buff / loot など「共通定義（shared）」が混ざらないようにする
- Project View（名前順）でも見やすい並びを固定する
- 新規追加時の判断基準を統一する

## 基本方針（結論）
- 並びは **番号プレフィックス（2桁固定）+ ドット** で制御する（例: `10.features.item`）
- 番号は **レンジ（帯）** でカテゴリ分離する
    - 00–09 : meta（このフォルダ）
    - 10–69 : features（機能追加の単位）
    - 70–89 : shared（共通定義）
    - 90–99 : work/temp（作業用）

## 迷ったときの判定
- それは「追加していく単位」か？ → features（10–69）
- それは「複数から参照される共通定義」か？ → shared（70–89）
- 作業中・一時退避か？ → work（99）

## 推奨ディレクトリ例（Database配下）
- `00.meta`
- `10.features.item`
- `20.features.class`
- `30.features.quest`
- `60.shared.buff`
- `70.shared.loot`
- `99.work`