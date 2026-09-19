package de.groupchat.core;

import java.util.*;

final class State {
    int schemaVersion = 1;
    Map<UUID, Group> groups = new LinkedHashMap<>();
    Map<UUID, String> players = new LinkedHashMap<>();
    Map<String, Long> lastInvites = new LinkedHashMap<>();

    static final class Group {
        UUID id;
        String name;
        UUID owner;
        long revision;
        Set<UUID> members = new LinkedHashSet<>();
        Set<UUID> coowners = new LinkedHashSet<>();
        Map<UUID, Invite> invitations = new LinkedHashMap<>();
        Map<UUID, Preference> preferences = new LinkedHashMap<>();
    }
    static final class Invite {
        UUID inviter;
        long expiresAt;
        Invite(UUID inviter, long expiresAt) { this.inviter = inviter; this.expiresAt = expiresAt; }
    }
    static final class Preference {
        String alias;
        String color = "white";
    }
}
