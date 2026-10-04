package com.minenorth_admin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Joueurs connus du serveur : en ligne + tous ceux qui ont un fichier world/playerdata/&lt;uuid&gt;.dat.
 * Noms : joueur en ligne, sinon cache local (usercache.json), jamais de requête réseau.
 */
public final class Players {
    private Players() {}

    public record Known(UUID id, String name, boolean online) {}

    public static File dataFile(MinecraftServer s, UUID id) {
        return s.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat").toFile();
    }

    @Nullable
    public static ServerPlayer online(MinecraftServer s, UUID id) {
        return s.getPlayerList().getPlayer(id);
    }

    /** Nom d'un joueur (en ligne ou non). */
    public static String name(MinecraftServer s, UUID id) {
        ServerPlayer p = online(s, id);
        if (p != null) return p.getGameProfile().getName();
        GameProfileCache c = s.getProfileCache();
        if (c != null) {
            Optional<GameProfile> g = c.get(id);
            if (g.isPresent() && g.get().getName() != null) return g.get().getName();
        }
        return id.toString().substring(0, 8);
    }

    /** Tous les joueurs connus, en ligne d'abord puis par nom. */
    public static List<Known> all(MinecraftServer s) {
        Map<UUID, Known> map = new LinkedHashMap<>();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            map.put(p.getUUID(), new Known(p.getUUID(), p.getGameProfile().getName(), true));
        }
        File[] files = s.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile().listFiles((dir, n) -> n.endsWith(".dat"));
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                try {
                    UUID id = UUID.fromString(n.substring(0, n.length() - 4));
                    if (!map.containsKey(id)) map.put(id, new Known(id, name(s, id), false));
                } catch (IllegalArgumentException ignored) {
                    // fichier qui n'est pas un joueur
                }
            }
        }
        List<Known> out = new ArrayList<>(map.values());
        out.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** Recherche par nom exact (insensible à la casse) parmi les joueurs connus. */
    @Nullable
    public static Known byName(MinecraftServer s, String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        ServerPlayer p = s.getPlayerList().getPlayerByName(n);
        if (p != null) return new Known(p.getUUID(), p.getGameProfile().getName(), true);
        for (Known k : all(s)) if (k.name().equalsIgnoreCase(n)) return k;
        return null;
    }
}
