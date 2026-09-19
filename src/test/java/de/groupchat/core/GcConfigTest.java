package de.groupchat.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GcConfigTest {
    @TempDir Path directory;
    @Test void generatesDocumentedDefaults() throws Exception {
        Path file = directory.resolve("groupchat.toml");
        assertEquals(new GcConfig(60, 3, 7), GcConfig.load(file));
        assertTrue(Files.readString(file).contains("invite_cooldown_seconds = 60"));
        assertTrue(Files.readString(file).contains("chat_log_retention_hours = 24"));
    }
    @Test void parsesRealTomlIncludingCommentsAndUnderscores() throws Exception {
        Path file = directory.resolve("groupchat.toml");
        Files.writeString(file, "invite_cooldown_seconds = 1_000 # seconds\nmax_owned_groups = 12\ninvite_expiry_days = 14\n");
        assertEquals(new GcConfig(1000, 12, 14), GcConfig.load(file));
    }
    @Test void invalidConfigFailsWithoutOverwritingIt() throws Exception {
        Path file = directory.resolve("groupchat.toml");
        for (String invalid : new String[]{"max_owned_groups = 0", "invite_cooldown_seconds = -1", "invite_expiry_days = 0",
                "max_owned_groups = 1.5", "invite_cooldown_seconds = '60'", "invite_expiry_days = 9999999999999",
                "chat_log_retention_hours = 0", "chat_log_retention_hours = -1", "chat_log_retention_hours = 87601",
                "chat_log_retention_hours = 1.5", "chat_log_retention_hours = '24'"}) {
            Files.writeString(file, invalid);
            assertThrows(RuntimeException.class, () -> GcConfig.load(file));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test void migratesOldConfigurationWithCustomValuesAndComments() throws Exception {
        Path old = directory.resolve("groupchat.toml");
        String original = "# Eigene Werte\ninvite_cooldown_seconds = 90\nmax_owned_groups = 5\ninvite_expiry_days = 14\n";
        Files.writeString(old, original);
        assertEquals(new GcConfig(90, 5, 14, 24), GcConfig.loadFromConfigDirectory(directory));
        Path migrated = directory.resolve("groupchats/groupchat.toml");
        assertFalse(Files.exists(old)); assertTrue(Files.readString(migrated).contains(original));
        assertTrue(Files.readString(migrated).contains("chat_log_retention_hours = 24"));
    }
    @Test void existingNewConfigurationTakesPrecedenceAndKeepsCustomRetention() throws Exception {
        Path current = directory.resolve("groupchats/groupchat.toml"); Files.createDirectories(current.getParent());
        Files.writeString(current, "chat_log_retention_hours = 48\nmax_owned_groups = 9\n");
        Path old = directory.resolve("groupchat.toml"); Files.writeString(old, "max_owned_groups = 5\n");
        assertEquals(new GcConfig(60, 9, 7, 48), GcConfig.loadFromConfigDirectory(directory));
        assertEquals("max_owned_groups = 5\n", Files.readString(old));
        assertEquals("chat_log_retention_hours = 48\nmax_owned_groups = 9\n", Files.readString(current));
    }
    @Test void migrationDoesNotMoveOrOverwriteAnInvalidLegacyFile() throws Exception {
        Path old = directory.resolve("groupchat.toml"); Files.writeString(old, "max_owned_groups = 0");
        assertThrows(IllegalArgumentException.class, () -> GcConfig.loadFromConfigDirectory(directory));
        assertEquals("max_owned_groups = 0", Files.readString(old));
        assertFalse(Files.exists(directory.resolve("groupchats/groupchat.toml")));
    }
    @Test void upgradesRootSettingWithoutAccidentallyAddingItToAnExistingTomlTable() throws Exception {
        Path file = directory.resolve("groupchat.toml");
        Files.writeString(file, "max_owned_groups = 4\n[notes]\nexample = 'keep'\n");
        assertEquals(24, GcConfig.load(file).chatLogRetentionHours());
        String upgraded = Files.readString(file);
        assertEquals(new GcConfig(60, 4, 7, 24), GcConfig.load(file));
        assertEquals(upgraded, Files.readString(file));
        assertTrue(upgraded.contains("[notes]\nexample = 'keep'"));
    }

    @Test void translatesLegacyDefaultCommentsWithoutChangingCustomCommentsOrValues() throws Exception {
        Path file = directory.resolve("groupchat.toml");
        String text = "# GroupChat – Änderungen werden beim nächsten Serverstart übernommen.\n"
                + "# Maximale Anzahl eigener Gruppen. Mitgliedschaften und Co-Owner-Rollen zählen nicht.\n"
                + "max_owned_groups = 8\n# Meine eigene Notiz\nchat_log_retention_hours = 48\n";
        Files.writeString(file, text);
        assertEquals(new GcConfig(60, 8, 7, 48), GcConfig.load(file));
        String updated = Files.readString(file);
        assertTrue(updated.contains("# GroupChat - Changes take effect after restarting the server."));
        assertTrue(updated.contains("# Maximum groups owned per player. Memberships and co-owner roles do not count."));
        assertTrue(updated.contains("# Meine eigene Notiz"));
        assertFalse(updated.contains("Änderungen"));
        GcConfig.load(file);
        assertEquals(updated, Files.readString(file));
    }

    @Test void commentTranslationPreservesWindowsLineEndingsAndIndentation() throws Exception {
        Path file = directory.resolve("groupchat.toml");
        Files.writeString(file, "  # Gilt gruppenübergreifend. 0 deaktiviert den Cooldown.\r\nchat_log_retention_hours = 24\r\n");
        GcConfig.load(file);
        assertEquals("  # Applies across all groups. Set to 0 to disable the cooldown.\r\nchat_log_retention_hours = 24\r\n", Files.readString(file));
    }

    @Test void invalidConfigurationIsNotRewrittenDuringCommentTranslation() throws Exception {
        Path file = directory.resolve("groupchat.toml");
        String text = "# Gilt gruppenübergreifend. 0 deaktiviert den Cooldown.\nmax_owned_groups = 0\n";
        Files.writeString(file, text);
        assertThrows(IllegalArgumentException.class, () -> GcConfig.load(file));
        assertEquals(text, Files.readString(file));
    }
}
