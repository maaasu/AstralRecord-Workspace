package io.github.maaasu.astralarchitect.ticket;

/**
 * チケットディレクトリ内の固定ファイル名です。
 */
public final class TicketFiles {

    public static final String METADATA = "ticket.json";
    public static final String SOURCE_SCHEMATIC = "source.schem";
    public static final String CANDIDATE_SCHEMATIC = "candidate.schem";
    public static final String APPLIED_SCHEMATIC = "applied.schem";
    public static final String LOCKS_DIRECTORY = ".locks";
    public static final String WORKER_LOCK = ".worker.lock";
    public static final String ATTACHMENTS = "attachments";

    private TicketFiles() {
    }
}
