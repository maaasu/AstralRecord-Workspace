package io.github.maaasu.astralRecord.feature.serverrecord.model;

import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;

/** @param type 表示種別 @param anchor 永続化する表示IDとワールド内の位置（textは未使用） */
public record ServerRecordPlacement(ServerRecordType type, TextDisplayPlacement anchor) { }
