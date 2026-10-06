package com.executionbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

final class LocalState {
    private final Path database;

    LocalState(Path database) {
        this.database = database;
    }

    String read(String key, String fallback) throws IOException {
        if (!Files.exists(database)) return fallback;
        for (String line : Files.readAllLines(database)) {
            int separator = line.indexOf(':');
            if (separator > 0 && line.substring(0, separator).trim().equals(key)) {
                return line.substring(separator + 1).trim();
            }
        }
        return fallback;
    }

    void write(String key, String value) throws IOException {
        Files.createDirectories(database.getParent());
        Map<String, String> values = new HashMap<>();
        if (Files.exists(database)) {
            for (String line : Files.readAllLines(database)) {
                int separator = line.indexOf(':');
                if (separator > 0) values.put(line.substring(0, separator).trim(),
                        line.substring(separator + 1).trim());
            }
        }
        values.put(key, value);
        StringBuilder output = new StringBuilder();
        values.forEach((name, item) -> output.append(name).append(':').append(item).append('\n'));
        Path temporary = database.resolveSibling(database.getFileName() + ".tmp");
        Files.writeString(temporary, output.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(temporary, database, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
