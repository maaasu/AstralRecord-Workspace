package io.github.maaasu.astralarchitect.ticket;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * Java worker間、またはPython CLIとの操作を直列化するOSファイルロックです。
 */
final class TicketFileLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    TicketFileLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * ロックが現在も保持されていることを確認します。
     *
     * @throws IOException ロックが失効している場合
     */
    void requireHeld() throws IOException {
        if (!lock.isValid()) {
            throw new IOException("repository lock is no longer held");
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.close();
        } finally {
            channel.close();
        }
    }
}
