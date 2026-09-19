package de.groupchat.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupServiceTest {
    @TempDir Path directory;
    final UUID owner = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID(), charlie = UUID.randomUUID();
    MutableClock clock;
    GroupService service;
    Path file;
    final GcConfig config = new GcConfig(60, 3, 7);
    @BeforeEach void setup() throws Exception {
        file = directory.resolve("groups.json");
        clock = new MutableClock();
        service = new GroupService(file, config, clock);
        service.rememberPlayers(Map.of(owner, "Owner", alice, "Alice", bob, "Bob", charlie, "Charlie"));
    }
    void members(String group, UUID... players) {
        for (UUID player : players) { service.invite(owner, group, player); service.respond(player, group, true); }
    }
    void denied(Runnable action) { assertThrows(GcException.class, action::run); }

    @ParameterizedTest @ValueSource(strings={"ab", "abcdefghijklmnopqrstu", "Mäuse", "a b", "a.b", "a/b", "a§b", ""})
    void rejectsInvalidNames(String name) { denied(() -> service.create(owner, name)); }
    @Test void acceptsBoundariesDigitsAndCaseInsensitiveUniqueness() {
        service.create(owner, "A_1"); service.create(owner, "abcdefghijklmnopqrst");
        denied(() -> service.create(alice, "a_1"));
        assertEquals("A_1", service.find("a_1").name());
    }
    @Test void reservesEveryCommandCaseInsensitively() {
        for (String command : GroupService.RESERVED) denied(() -> service.create(owner, command.toUpperCase(Locale.ROOT)));
    }
    @Test void limitCountsOwnedGroupsOnlyAndCanBeConfigured() throws Exception {
        service.create(owner, "One"); service.create(owner, "Two"); service.create(owner, "Three");
        denied(() -> service.create(owner, "Four"));
        service.create(alice, "AliceGroup"); service.invite(alice, "AliceGroup", owner); service.respond(owner, "AliceGroup", true);
        assertEquals(4, service.groupsFor(owner).size());
        service = new GroupService(file, new GcConfig(60, 4, 7), clock);
        service.create(owner, "Four");
    }
    @Test void memberAndOutsiderCannotManageOrReadChat() {
        service.create(owner, "Base"); members("Base", alice);
        denied(() -> service.rename(alice, "Base", "Other"));
        denied(() -> service.invite(alice, "Base", bob));
        denied(() -> service.kick(alice, "Base", owner));
        denied(() -> service.forChat(bob, "Base"));
        denied(() -> service.color(bob, "Base", "red"));
        denied(() -> service.shorten(bob, "Base", "b"));
        denied(() -> service.delete(alice, "Base", false, false));
    }
    @Test void ownershipOverrideAppliesWithoutChangingConfiguredDefault() {
        service.create(owner, "One"); service.create(owner, "Two"); service.create(owner, "Three");
        service.create(owner, "Four", 4);
        denied(() -> service.create(owner, "Five", 4));
        denied(() -> service.create(alice, "Zero", 0));
        assertEquals(3, service.defaultOwnershipLimit());
        assertEquals(4, service.groupsFor(owner).size());
    }
    @Test void transferRechecksOverrideOnConfirmationForOwnersAndAdministrators() {
        service.create(owner, "Base"); members("Base", alice);
        service.create(alice, "AliceGroup");
        for (boolean admin : new boolean[]{false, true}) {
            denied(() -> service.transfer(owner, "Base", alice, admin, false, 1));
            assertFalse(service.transfer(owner, "Base", alice, admin, false, 2));
            denied(() -> service.transfer(owner, "Base", alice, admin, true, 1));
            assertEquals(owner, service.find("Base").owner());
        }
        assertFalse(service.transfer(owner, "Base", alice, false, false, 2));
        assertTrue(service.transfer(owner, "Base", alice, false, true, 2));
    }
    @Test void coownerCanManageButCannotEscalateOrKickPeers() {
        service.create(owner, "Base"); members("Base", alice, bob, charlie);
        service.coowner(owner, "Base", alice, true); service.coowner(owner, "Base", bob, true);
        service.rename(alice, "Base", "Renamed");
        denied(() -> service.coowner(alice, "Renamed", charlie, true));
        denied(() -> service.coowner(alice, "Renamed", bob, false));
        denied(() -> service.delete(alice, "Renamed", false, false));
        denied(() -> service.transfer(alice, "Renamed", bob, false, false));
        denied(() -> service.kick(alice, "Renamed", owner));
        denied(() -> service.kick(alice, "Renamed", bob));
        service.kick(alice, "Renamed", charlie);
        service.invite(alice, "Renamed", charlie);
        service.respond(charlie, "Renamed", true);
        service.coowner(owner, "Renamed", alice, false);
        denied(() -> service.invite(alice, "Renamed", UUID.randomUUID()));
        service.kick(owner, "Renamed", bob);
        assertFalse(service.find("Renamed").coowners().contains(bob));
    }
    @Test void coownerMustBeExistingMemberAndOwnerCannotBeCoowner() {
        service.create(owner, "Base");
        denied(() -> service.coowner(owner, "Base", alice, true));
        denied(() -> service.coowner(owner, "Base", owner, true));
    }
    @Test void cooldownIsSenderRecipientPairAcrossGroupsAndSurvivesRestart() throws Exception {
        service.create(owner, "One"); service.create(owner, "Two"); service.create(alice, "Other");
        service.invite(owner, "One", bob);
        denied(() -> service.invite(owner, "Two", bob));
        service.invite(alice, "Other", bob); // Different sender may invite the same recipient.
        service.invite(owner, "Two", charlie); // Same sender may invite a different recipient.
        service = new GroupService(file, config, clock);
        clock.advance(59_999); denied(() -> service.invite(owner, "Two", bob));
        clock.advance(1); service.invite(owner, "Two", bob);
    }
    @Test void acceptingOrDecliningDoesNotBypassCooldown() {
        service.create(owner, "One"); service.create(owner, "Two");
        service.invite(owner, "One", bob); service.respond(bob, "One", false);
        denied(() -> service.invite(owner, "Two", bob));
        clock.advance(60_000); service.invite(owner, "One", bob); service.respond(bob, "One", true);
        service.leave(bob, "One"); denied(() -> service.invite(owner, "Two", bob));
    }
    @Test void pendingInvitationCannotBeRepeatedEvenAfterCooldown() {
        service.create(owner, "Base"); service.invite(owner, "Base", bob);
        clock.advance(60_000); denied(() -> service.invite(owner, "Base", bob));
    }
    @Test void cooldownCanBeDisabled() throws Exception {
        service = new GroupService(file, new GcConfig(0, 3, 7), clock);
        service.create(owner, "One"); service.create(owner, "Two");
        service.invite(owner, "One", bob); service.invite(owner, "Two", bob);
    }
    @Test void offlineInvitationSurvivesRenameAndRestartAndExpiresAtSevenDays() throws Exception {
        service.create(owner, "Base"); service.invite(owner, "Base", alice);
        service.rename(owner, "Base", "NewBase");
        service = new GroupService(file, config, clock);
        assertEquals("NewBase", service.invitations(alice).getFirst().group().name());
        clock.advance(7 * 86_400_000L - 1); assertEquals(1, service.invitations(alice).size());
        clock.advance(1); assertTrue(service.invitations(alice).isEmpty());
        denied(() -> service.respond(alice, "NewBase", true));
        service.invite(owner, "NewBase", alice); service.respond(alice, "NewBase", true);
        assertTrue(service.find("NewBase").members().contains(alice));
    }
    @Test void acceptRequiresInvitationAndDeclineConsumesIt() {
        service.create(owner, "Base");
        denied(() -> service.respond(alice, "Base", true));
        service.invite(owner, "Base", alice); service.respond(alice, "Base", false);
        denied(() -> service.respond(alice, "Base", true));
        assertFalse(service.find("Base").members().contains(alice));
    }
    @Test void revokedCoownerCannotLeaveInvitationsBehind() {
        service.create(owner, "Base"); members("Base", alice);
        service.coowner(owner, "Base", alice, true); service.invite(alice, "Base", bob);
        service.coowner(owner, "Base", alice, false);
        assertTrue(service.invitations(bob).isEmpty());
    }
    @Test void colorsAndAliasesArePersonalPersistentAndSurviveRename() throws Exception {
        var g = service.create(owner, "Base"); members("Base", alice);
        service.shorten(owner, "Base", "B"); service.shorten(alice, "Base", "ba");
        service.color(owner, "Base", "GREEN"); service.color(alice, "Base", "blue");
        service.rename(owner, "Base", "NewBase");
        service = new GroupService(file, config, clock);
        assertEquals(g.id(), service.forChat(owner, "b").id());
        assertEquals("NewBase", service.forChat(alice, "BA").name());
        assertEquals("green", service.preference(owner, g.id()).color());
        assertEquals("blue", service.preference(alice, g.id()).color());
        denied(() -> service.forChat(owner, "ba"));
        service.shorten(owner, "NewBase", null); denied(() -> service.forChat(owner, "b"));
    }
    @Test void aliasesAreUniquePerPlayerAndLimitedToTwoCharacters() {
        service.create(owner, "One"); service.create(owner, "Two");
        members("One", alice);
        service.shorten(owner, "One", "B"); service.shorten(alice, "One", "b");
        denied(() -> service.shorten(owner, "Two", "b"));
        for (String alias : List.of("abc", "gc", "ä", "a b", "")) denied(() -> service.shorten(owner, "One", alias));
        service.shorten(owner, "One", "2-"); assertEquals("One", service.forChat(owner, "2-").name());
        denied(() -> service.color(owner, "One", "bold"));
        denied(() -> service.color(owner, "One", "#ff0000"));
        for (String color : GroupService.COLORS) service.color(owner, "One", color);
    }
    @Test void leavingClearsRoleAndPreferencesButOwnerMustTransferFirst() {
        var g = service.create(owner, "Base"); members("Base", alice);
        service.coowner(owner, "Base", alice, true); service.shorten(alice, "Base", "b"); service.color(alice, "Base", "red");
        denied(() -> service.leave(owner, "Base"));
        service.leave(alice, "Base");
        denied(() -> service.forChat(alice, "b"));
        clock.advance(60_000); members("Base", alice);
        assertEquals(new GroupService.PreferenceView(null, "white"), service.preference(alice, g.id()));
        assertFalse(service.find("Base").coowners().contains(alice));
    }
    @Test void deletionRequiresExactActorActionAndThirtySecondWindow() {
        service.create(owner, "One"); service.create(owner, "Two");
        denied(() -> service.delete(owner, "One", false, true));
        assertFalse(service.delete(owner, "One", false, false));
        denied(() -> service.delete(owner, "Two", false, true));
        service.delete(owner, "One", false, false);
        denied(() -> service.delete(alice, "One", true, true));
        clock.advance(30_000); denied(() -> service.delete(owner, "One", false, true));
        service.delete(owner, "One", false, false); clock.advance(29_999);
        assertTrue(service.delete(owner, "One", false, true));
        denied(() -> service.find("One"));
    }
    @Test void deletionCleansInvitesPreferencesAndNameCanBeReused() {
        service.create(owner, "Base"); service.invite(owner, "Base", alice); service.shorten(owner, "Base", "b");
        service.delete(owner, "Base", false, false); service.delete(owner, "Base", false, true);
        assertTrue(service.invitations(alice).isEmpty()); denied(() -> service.forChat(owner, "b"));
        service.create(alice, "base");
    }
    @Test void confirmationDoesNotSurviveRestartRenameOrRoleChange() throws Exception {
        service.create(owner, "Base"); members("Base", alice);
        service.delete(owner, "Base", false, false);
        service = new GroupService(file, config, clock); denied(() -> service.delete(owner, "Base", false, true));
        service.delete(owner, "Base", false, false); service.rename(owner, "Base", "Renamed");
        denied(() -> service.delete(owner, "Renamed", false, true));
        service.delete(owner, "Renamed", false, false); service.coowner(owner, "Renamed", alice, true);
        denied(() -> service.delete(owner, "Renamed", false, true));
    }
    @Test void transferDemotesFormerOwnerAndRequiresExactTarget() {
        service.create(owner, "Base"); members("Base", alice, bob);
        service.coowner(owner, "Base", alice, true);
        service.transfer(owner, "Base", alice, false, false);
        denied(() -> service.transfer(owner, "Base", bob, false, true));
        service.transfer(owner, "Base", alice, false, false);
        assertTrue(service.transfer(owner, "Base", alice, false, true));
        var g = service.find("Base");
        assertEquals(alice, g.owner()); assertTrue(g.members().contains(owner));
        assertFalse(g.coowners().contains(owner)); assertFalse(g.coowners().contains(alice));
        denied(() -> service.delete(owner, "Base", false, false));
    }
    @Test void administrativeTransferStillRequiresMemberAndAvailableOwnershipSlot() {
        service.create(owner, "Base"); members("Base", alice);
        denied(() -> service.transfer(null, "Base", bob, true, false));
        service.create(alice, "One"); service.create(alice, "Two"); service.create(alice, "Three");
        denied(() -> service.transfer(null, "Base", alice, true, false));
        service.delete(alice, "Three", false, false); service.delete(alice, "Three", false, true);
        service.transfer(null, "Base", alice, true, false); service.transfer(null, "Base", alice, true, true);
        assertEquals(alice, service.find("Base").owner());
        service.delete(null, "Base", true, false); assertTrue(service.delete(null, "Base", true, true));
    }
    @Test void transferRechecksLimitAndMembershipAtConfirmation() {
        service.create(owner, "Base"); members("Base", alice);
        service.transfer(owner, "Base", alice, false, false);
        service.create(alice, "One"); service.create(alice, "Two"); service.create(alice, "Three");
        denied(() -> service.transfer(owner, "Base", alice, false, true));
        service.leave(alice, "Base"); denied(() -> service.transfer(owner, "Base", alice, false, true));
        assertEquals(owner, service.find("Base").owner());
    }
    @Test void adminAndOwnerConfirmationsDoNotCrossAndDisconnectClearsThem() {
        service.create(owner, "Base");
        service.delete(owner, "Base", false, false); denied(() -> service.delete(owner, "Base", true, true));
        service.forgetConfirmations(owner); denied(() -> service.delete(owner, "Base", false, true));
    }
    @Test void uuidMembershipSurvivesPlayerRename() {
        var g = service.create(owner, "Base");
        service.rememberPlayers(Map.of(owner, "NewOwner"));
        assertEquals(owner, service.playerId("newowner"));
        assertEquals(g.id(), service.forChat(owner, "Base").id());
        denied(() -> service.playerId("Owner"));
    }
    @Test void corruptDataIsNeverOverwrittenAndBackupIsPresent() throws Exception {
        service.create(owner, "Base"); assertTrue(Files.exists(directory.resolve("groups.json.bak")));
        Files.writeString(file, "{broken");
        assertThrows(java.io.IOException.class, () -> new GroupService(file, config, clock));
        assertEquals("{broken", Files.readString(file));
    }
    @Test void futureSchemaAndInvalidMembershipAreRejected() throws Exception {
        service.create(owner, "Base");
        String valid = Files.readString(file);
        Files.writeString(file, valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));
        assertThrows(java.io.IOException.class, () -> new GroupService(file, config, clock));
        var state = StateStore.JSON.fromJson(valid, State.class);
        state.groups.values().iterator().next().members.clear();
        Files.writeString(file, StateStore.JSON.toJson(state));
        assertThrows(java.io.IOException.class, () -> new GroupService(file, config, clock));
    }
    @Test void saveFailureRollsBackInMemoryState() throws Exception {
        service.create(owner, "Base");
        Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("blocker"), "x");
        assertThrows(java.io.UncheckedIOException.class, () -> service.rename(owner, "Base", "Lost"));
        assertEquals("Base", service.find("Base").name()); denied(() -> service.find("Lost"));
    }
    static final class MutableClock extends Clock {
        long millis = 1_800_000_000_000L;
        void advance(long delta) { millis += delta; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
        public long millis() { return millis; }
    }
}
