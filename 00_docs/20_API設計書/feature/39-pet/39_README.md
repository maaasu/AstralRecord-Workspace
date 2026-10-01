# ペットAPI

野生卵、施設孵化、成長、配合、装備選択、死亡・復活の動的正本を扱う。

- [[39_1.00-モデル定義]]
- [[39_3.00-ペットAPI]]
- DB: [[dbo.pet_instance]]、[[dbo.account_pet_state]]、[[dbo.pet_operation]]

マスターは `55.features.pet/v1.pets.yml` の単一 `pets`。既存DBには `20261001_pet.sql` を新API配置前に適用する。
