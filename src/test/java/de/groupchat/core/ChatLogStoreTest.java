package de.groupchat.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatLogStoreTest {
    @TempDir Path directory;
    final GroupServiceTest.MutableClock clock = new GroupServiceTest.MutableClock();
    final UUID sender = UUID.randomUUID();
    GroupService.GroupView group(String name) { return new GroupService.GroupView(UUID.randomUUID(), name, sender, Set.of(sender), Set.of()); }
    Path file(GroupService.GroupView group) { return directory.resolve(group.id() + ".jsonl"); }

    @Test void disabledLoggingCreatesNoDirectoryOrMessages() throws Exception {
        Path missing = directory.resolve("logs");
        var logs = new ChatLogStore(missing, -1, clock);
        assertFalse(logs.enabled());
        logs.append(group("Base"), sender, "Alex", "Not logged");
        logs.cleanup();
        assertFalse(Files.exists(missing));
    }
    @Test void disabledLoggingDoesNotAccessAnUnusableLogLocation() throws Exception {
        Path blocked = directory.resolve("logs");
        Files.writeString(blocked, "A file instead of a directory");
        var logs = new ChatLogStore(blocked, -1, clock);
        logs.append(group("Base"), sender, "Alex", "Still delivered");
        logs.cleanup();
        assertEquals("A file instead of a directory", Files.readString(blocked));
    }
    @Test void disablingPreservesExistingLogsAndReenablingResumesCleanup() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock); var group = group("Base");
        logs.append(group, sender, "Alex", "Previously logged");
        String original = Files.readString(file(group));
        clock.advance(25 * 3_600_000L);
        var disabled = new ChatLogStore(directory, -1, clock);
        disabled.append(group, sender, "Alex", "Must not be logged");
        disabled.cleanup();
        assertEquals(original, Files.readString(file(group)));
        assertTrue(new ChatLogStore(directory, 24, clock).enabled());
        assertFalse(Files.exists(file(group)));
    }

    @Test void writesSeparateGroupLogsWithCompleteMetadataAndEscapesMessages() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock);
        var one = group("Base1"); var two = group("Base2");
        String message = "Hallo \"Mods\"!\nGefälschte Zeile? \\ 💬";
        logs.append(one, sender, "Alex", message); logs.append(two, sender, "Alex", "Andere Gruppe");
        assertEquals(1, Files.readAllLines(file(one)).size());
        var row = JsonParser.parseString(Files.readString(file(one))).getAsJsonObject();
        assertEquals(clock.instant().toString(), row.get("timestamp").getAsString());
        assertEquals(one.id().toString(), row.get("group_id").getAsString());
        assertEquals("Base1", row.get("group_name").getAsString());
        assertEquals(sender.toString(), row.get("sender_uuid").getAsString());
        assertEquals("Alex", row.get("sender_name").getAsString());
        assertEquals(message, row.get("message").getAsString());
        assertFalse(Files.readString(file(two)).contains("Gefälschte"));
    }
    @Test void renameKeepsOneFileAndHistoricalGroupNames() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock); var before = group("Before");
        logs.append(before, sender, "Alex", "Erste Nachricht");
        var after = new GroupService.GroupView(before.id(), "After", sender, before.members(), before.coowners());
        logs.append(after, sender, "Alex", "Zweite Nachricht");
        var lines = Files.readAllLines(file(before));
        assertEquals(2, lines.size()); assertTrue(lines.get(0).contains("Before")); assertTrue(lines.get(1).contains("After"));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void removesOnlyExpiredMessagesAtTheExactRollingBoundary() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock); var group = group("Base");
        logs.append(group, sender, "Alex", "old");
        clock.advance(23 * 3_600_000L); logs.append(group, sender, "Alex", "recent");
        clock.advance(3_600_000L - 1); logs.cleanup(); assertEquals(2, Files.readAllLines(file(group)).size());
        clock.advance(1); logs.cleanup();
        assertEquals(1, Files.readAllLines(file(group)).size()); assertTrue(Files.readString(file(group)).contains("recent"));
        try (var files = Files.list(directory)) { assertEquals(List.of(file(group)), files.toList()); }
    }
    @Test void cleansInactiveAndDeletedGroupsAfterRestartAndHonorsNewRetention() throws Exception {
        var logs = new ChatLogStore(directory, 48, clock); var group = group("Deleted");
        logs.append(group, sender, "Alex", "old"); clock.advance(25 * 3_600_000L);
        // No GroupService is involved: cleanup does not depend on a group still existing or being active.
        new ChatLogStore(directory, 24, clock);
        assertFalse(Files.exists(file(group)));
    }
    @Test void configurableLongerRetentionKeepsRecentMessagesAndShorterRetentionDeletesThem() throws Exception {
        var logs = new ChatLogStore(directory, 48, clock); var group = group("Base");
        logs.append(group, sender, "Alex", "retain"); clock.advance(25 * 3_600_000L); logs.cleanup();
        assertTrue(Files.exists(file(group)));
        clock.advance(23 * 3_600_000L); logs.cleanup(); assertFalse(Files.exists(file(group)));
    }
    @Test void trustsMessageTimestampInsteadOfFileModificationTimeAndAvoidsUnneededRewrites() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock); var group = group("Base");
        logs.append(group, sender, "Alex", "recent");
        FileTime old = FileTime.from(Instant.EPOCH); Files.setLastModifiedTime(file(group), old);
        logs.cleanup(); assertEquals(old, Files.getLastModifiedTime(file(group)));
        assertTrue(Files.readString(file(group)).contains("recent"));
    }
    @Test void leavesUnrelatedFilesAloneAndRemovesEmptyAndOrphanedTemporaryFiles() throws Exception {
        var empty = group("Empty"); var temporary = directory.resolve(empty.id() + ".jsonl.tmp");
        Files.writeString(file(empty), ""); Files.writeString(temporary, "old temporary data");
        Path unrelated = directory.resolve("administrator-notes.txt"); Files.writeString(unrelated, "keep");
        new ChatLogStore(directory, 24, clock);
        assertFalse(Files.exists(file(empty))); assertFalse(Files.exists(temporary)); assertEquals("keep", Files.readString(unrelated));
    }
    @Test void damagedFileDoesNotPreventOtherGroupsFromExpiringAndIsNotOverwritten() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock); var broken = group("Broken"); var other = group("Other");
        logs.append(broken, sender, "Alex", "old"); logs.append(other, sender, "Alex", "old");
        Files.writeString(file(broken), "broken\n", StandardOpenOption.APPEND);
        String original = Files.readString(file(broken)); clock.advance(25 * 3_600_000L);
        assertThrows(IOException.class, logs::cleanup);
        assertEquals(original, Files.readString(file(broken))); assertFalse(Files.exists(file(other)));
        assertFalse(Files.exists(directory.resolve(broken.id() + ".jsonl.tmp")));
    }
    @Test void reportsAppendFailuresInsteadOfSilentlyLosingModerationEvidence() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock); var group = group("Base");
        Files.createDirectory(file(group));
        assertThrows(IOException.class, () -> logs.append(group, sender, "Alex", "cannot write"));
    }
    @Test void concurrentAppendAndCleanupDoNotLoseOrDuplicateMessages() throws Exception {
        var logs = new ChatLogStore(directory, 24, clock); var group = group("Base");
        try (var pool = Executors.newFixedThreadPool(3)) {
            var append = pool.submit(() -> { for (int i = 0; i < 100; i++) logs.append(group, sender, "Alex", "m" + i); return null; });
            var prune = pool.submit(() -> { for (int i = 0; i < 20; i++) logs.cleanup(); return null; });
            append.get(10, TimeUnit.SECONDS); prune.get(10, TimeUnit.SECONDS);
        }
        var lines = Files.readAllLines(file(group)); assertEquals(100, lines.size());
        assertEquals(100, new HashSet<>(lines).size());
    }
}
