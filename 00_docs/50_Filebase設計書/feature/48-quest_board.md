# Quest Board 設計

## 役割

Quest Board は、複数の quest をプレイヤーへ提示し、選択・受注する導線です。

## 設計方針

- 同じ場所、目的、進行帯など、プレイヤーが理解できる単位で quest をまとめます。
- 掲示数を増やしすぎず、現在選ぶ意味がある quest を優先します。
- quest board 自体へ quest の条件や報酬を重複定義しません。
- 表示順は、必須導線、現在帯、反復、上位予告の順を基本にします。

## progression

掲載 quest のうち、最も早く利用させたい quest の段階を基準にします。大きく異なる progression の quest を同じ board に混在させる場合は、表示条件で分けます。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\48.features.quest_board\docs.quest_board.YAMLスキーマ定義.md`
