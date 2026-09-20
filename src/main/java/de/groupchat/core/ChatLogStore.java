package de.groupchat.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ChatLogStore {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern OWN_FILE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jsonl");
    private final Path directory;
    private final Duration retention;
    private final Clock clock;
    private final boolean enabled;

    public ChatLogStore(Path directory, long retentionHours, Clock clock) throws IOException {
        if (retentionHours != -1 && (retentionHours < 1 || retentionHours > 87_600)) throw new IllegalArgumentException("Invalid log retention period. Use -1 to disable logging, or 1 to 87600 hours.");
        this.directory = directory;
        this.enabled = retentionHours != -1;
        this.retention = enabled ? Duration.ofHours(retentionHours) : Duration.ZERO;
        this.clock = Objects.requireNonNull(clock);
        if (enabled) {
            Files.createDirectories(directory);
            cleanup();
        }
    }

    public boolean enabled() { return enabled; }

    public synchronized void append(GroupService.GroupView group, UUID sender, String senderName, String message) throws IOException {
        if (!enabled) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", clock.instant().toString());
        entry.addProperty("group_id", group.id().toString());
        entry.addProperty("group_name", group.name());
        entry.addProperty("sender_uuid", sender.toString());
        entry.addProperty("sender_name", senderName);
        entry.addProperty("message", message);
        Path file = directory.resolve(group.id() + ".jsonl");
        if (Files.isSymbolicLink(file)) throw new IOException("Log file must not be a symbolic link: " + file);
        Files.writeString(file, JSON.toJson(entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Prune by message timestamp, including inactive and deleted groups. */
    public synchronized void cleanup() throws IOException {
        if (!enabled) return;
        Instant cutoff = clock.instant().minus(retention);
        IOException failure = null;
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    if (name.endsWith(".tmp") && OWN_FILE.matcher(name.substring(0, name.length() - 4)).matches()) {
                        // Remove temporary files left by an interrupted prune.
                        Files.delete(file);
                    } else if (OWN_FILE.matcher(name).matches()) {
                        prune(file, cutoff);
                    }
                } catch (IOException e) {
                    if (failure == null) failure = new IOException("One or more GroupChat logs could not be cleaned up.");
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private void prune(Path file, Instant cutoff) throws IOException {
        // Avoid rewriting logs that contain no expired entries.
        boolean expired = false;
        try (var input = Files.newBufferedReader(file)) {
            String line;
            boolean any = false;
            while ((line = input.readLine()) != null) {
                any = true;
                if (line.isBlank() || !timestamp(line, file).isAfter(cutoff)) { expired = true; break; }
            }
            if (!any) expired = true;
        }
        if (!expired) return;
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        if (Files.isSymbolicLink(temporary)) throw new IOException("Temporary log file must not be a symbolic link: " + temporary);
        boolean removed = false;
        long kept = 0;
        try {
            try (var input = Files.newBufferedReader(file); var output = Files.newBufferedWriter(temporary)) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.isBlank()) { removed = true; continue; }
                    Instant timestamp = timestamp(line, file);
                    if (timestamp.isAfter(cutoff)) {
                        output.write(line); output.newLine(); kept++;
                    } else removed = true;
                }
            }
            if (kept == 0) Files.delete(file);
            else if (removed) {
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Instant timestamp(String line, Path file) throws IOException {
        try { return Instant.parse(JsonParser.parseString(line).getAsJsonObject().get("timestamp").getAsString()); }
        catch (RuntimeException e) {
            throw new IOException("Invalid timestamp in " + file + ". The log file has not been changed.", e);
        }
    }
}
