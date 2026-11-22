package com.rupeepulse;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.time.LocalDate;

final class DailyModelBudget {
    static final class Limit extends RuntimeException {
        Limit() { super("The public demo has reached today's AI allowance. The ledger still works. Try AI again after midnight UTC."); }
    }
    private final Path directory;
    private final int maximum;
    private final Clock clock;
    DailyModelBudget(Path directory, int maximum, Clock clock) {
        if (maximum < 0) throw new IllegalArgumentException("AI call allowance cannot be negative.");
        this.directory = directory;
        this.maximum = maximum;
        this.clock = clock;
    }

    // /home survives App Service restarts. Reserve before sending. Fail closed on storage errors.
    synchronized void reserve() throws IOException {
        Files.createDirectories(directory);
        Path count = directory.resolve("calls-" + LocalDate.now(clock));
        try (var channel = FileChannel.open(directory.resolve("quota.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            int used = 0;
            if (Files.exists(count)) {
                try { used = Integer.parseInt(Files.readString(count).trim()); }
                catch (NumberFormatException e) { throw new IOException("Invalid persisted AI budget counter.", e); }
            }
            if (used < 0) throw new IOException("Invalid persisted AI budget counter.");
            if (used >= maximum) throw new Limit();
            var tmp = directory.resolve("next-count");
            Files.writeString(tmp, Integer.toString(used + 1), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, count, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
