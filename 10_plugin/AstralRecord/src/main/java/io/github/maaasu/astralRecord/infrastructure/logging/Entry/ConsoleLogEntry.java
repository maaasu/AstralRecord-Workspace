package io.github.maaasu.astralRecord.infrastructure.logging.Entry;

import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogEntry;
import io.github.maaasu.astralRecord.infrastructure.logging.LogEntryMeta;

@LogEntryMeta(
        logDir         = "logs/console",
        csvHeader      = "Timestamp,Prefix,Log",
        fileNamePrefix = "console"
)
public record ConsoleLogEntry(String timestamp, String prefix, String log) implements LogEntry {
    @Override
    public String toCsvRow() {
        return String.format("\"%s\",\"%s\",\"%s\"",
                timestamp,
                prefix,
                log
        );
    }

    @Override
    public boolean isEnabled() {
        return ConfigProperties.getInstance().isLoggingEntryConsoleLogEntryEnabled();
    }
}
