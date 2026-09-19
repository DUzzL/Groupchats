package de.groupchat.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One complete snapshot per transaction; never replace unreadable data with an empty state. */
final class StateStore {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    StateStore(Path file) { this.file = file; }

    State load() throws IOException {
        if (Files.notExists(file)) return new State();
        try {
            String json = Files.readString(file);
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("schemaVersion") || !root.has("groups") || !root.has("players") || !root.has("lastInvites"))
                throw new IllegalStateException("Incomplete GroupChat data file");
            State state = JSON.fromJson(root, State.class);
            GroupService.validate(state);
            return state;
        } catch (RuntimeException e) {
            throw new IOException("Invalid GroupChat data: " + file + ". Check the file or restore its .bak backup.", e);
        }
    }

    void save(State state) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "groupchat-", ".tmp");
        try {
            byte[] bytes = JSON.toJson(state).getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
