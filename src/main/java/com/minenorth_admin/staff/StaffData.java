package com.minenorth_admin.staff;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rôles staff, membres et journal des actions. Sauvegardé dans world/data/minenorth_admin.dat.
 * Les rôles ne sont visibles nulle part côté joueur : seulement dans le panneau.
 */
public class StaffData extends SavedData {
    private static final String NAME = "minenorth_admin";
    public static final int MAX_RANK = 999;

    public static class Role {
        public final String id;
        public String name;
        /** Hiérarchie : on ne gère que les rôles (et membres) de rang strictement inférieur au sien. */
        public int rank;
        public final EnumSet<Perm> perms = EnumSet.noneOf(Perm.class);

        public Role(String id, String name, int rank) {
            this.id = id;
            this.name = name;
            this.rank = rank;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Id", id);
            t.putString("Name", name);
            t.putInt("Rank", rank);
            ListTag l = new ListTag();
            for (Perm p : perms) l.add(StringTag.valueOf(p.name()));
            t.put("Perms", l);
            return t;
        }

        static Role load(CompoundTag t) {
            Role r = new Role(t.getString("Id"), t.getString("Name"), t.getInt("Rank"));
            ListTag l = t.getList("Perms", Tag.TAG_STRING);
            for (int i = 0; i < l.size(); i++) {
                Perm p = Perm.byName(l.getString(i));
                if (p != null) r.perms.add(p);
            }
            return r;
        }
    }

    public static class Member {
        public String name;
        public String role;

        public Member(String name, String role) {
            this.name = name;
            this.role = role;
        }
    }

    public record LogEntry(long time, String actor, String target, String module, String text) {}

    private final Map<String, Role> roles = new LinkedHashMap<>();
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final Deque<LogEntry> logs = new ArrayDeque<>();
    private int nextRole = 1;

    public static StaffData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StaffData::load, StaffData::create, NAME);
    }

    /** Premier lancement : trois rôles d'exemple, modifiables ou supprimables. */
    private static StaffData create() {
        StaffData d = new StaffData();
        Role mod = d.addRole("Modérateur", 10);
        mod.perms.addAll(List.of(Perm.PANEL, Perm.BANK_VIEW, Perm.PERMIS_VIEW, Perm.GARAGE_VIEW, Perm.INV_VIEW, Perm.TELEPORT));
        Role admin = d.addRole("Admin", 50);
        admin.perms.addAll(EnumSet.allOf(Perm.class));
        admin.perms.remove(Perm.STAFF);
        Role boss = d.addRole("Gérant", 90);
        boss.perms.addAll(EnumSet.allOf(Perm.class));
        d.setDirty();
        return d;
    }

    public static StaffData load(CompoundTag tag) {
        StaffData d = new StaffData();
        d.nextRole = Math.max(1, tag.getInt("NextRole"));
        ListTag rl = tag.getList("Roles", Tag.TAG_COMPOUND);
        for (int i = 0; i < rl.size(); i++) {
            Role r = Role.load(rl.getCompound(i));
            if (!r.id.isEmpty()) d.roles.put(r.id, r);
        }
        ListTag ml = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < ml.size(); i++) {
            CompoundTag t = ml.getCompound(i);
            if (!t.hasUUID("Id")) continue;
            d.members.put(t.getUUID("Id"), new Member(t.getString("Name"), t.getString("Role")));
        }
        ListTag ll = tag.getList("Logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < ll.size(); i++) {
            CompoundTag t = ll.getCompound(i);
            d.logs.addLast(new LogEntry(t.getLong("T"), t.getString("A"), t.getString("P"), t.getString("M"), t.getString("X")));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("NextRole", nextRole);
        ListTag rl = new ListTag();
        for (Role r : roles.values()) rl.add(r.save());
        tag.put("Roles", rl);
        ListTag ml = new ListTag();
        members.forEach((id, m) -> {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putString("Name", m.name);
            t.putString("Role", m.role);
            ml.add(t);
        });
        tag.put("Members", ml);
        ListTag ll = new ListTag();
        for (LogEntry e : logs) {
            CompoundTag t = new CompoundTag();
            t.putLong("T", e.time());
            t.putString("A", e.actor());
            t.putString("P", e.target());
            t.putString("M", e.module());
            t.putString("X", e.text());
            ll.add(t);
        }
        tag.put("Logs", ll);
        return tag;
    }

    // ------------------------------------------------------------------ rôles

    public Role addRole(String name, int rank) {
        String id = "r" + nextRole++;
        Role r = new Role(id, name, rank);
        roles.put(id, r);
        setDirty();
        return r;
    }

    @Nullable
    public Role role(String id) {
        return id == null ? null : roles.get(id);
    }

    /** Rôles du plus haut au plus bas rang. */
    public List<Role> roles() {
        List<Role> l = new ArrayList<>(roles.values());
        l.sort((a, b) -> b.rank != a.rank ? Integer.compare(b.rank, a.rank) : a.name.compareToIgnoreCase(b.name));
        return l;
    }

    /** Supprime le rôle ; ses membres perdent leur accès. */
    public int deleteRole(String id) {
        if (roles.remove(id) == null) return -1;
        int n = 0;
        var it = members.values().iterator();
        while (it.hasNext()) {
            if (it.next().role.equals(id)) {
                it.remove();
                n++;
            }
        }
        setDirty();
        return n;
    }

    // ------------------------------------------------------------------ membres

    @Nullable
    public Member member(UUID id) {
        return members.get(id);
    }

    public Map<UUID, Member> members() {
        return members;
    }

    public void setMember(UUID id, String name, String role) {
        members.put(id, new Member(name, role));
        setDirty();
    }

    public boolean removeMember(UUID id) {
        boolean r = members.remove(id) != null;
        if (r) setDirty();
        return r;
    }

    // ------------------------------------------------------------------ journal

    public void log(LogEntry e, int max) {
        logs.addLast(e);
        while (logs.size() > Math.max(50, max)) logs.removeFirst();
        setDirty();
    }

    /** Les n entrées les plus récentes, de la plus récente à la plus ancienne. */
    public List<LogEntry> recentLogs(int n) {
        List<LogEntry> out = new ArrayList<>();
        var it = logs.descendingIterator();
        while (it.hasNext() && out.size() < n) out.add(it.next());
        return out;
    }
}
