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

    /**
     * Nom affiché dans le panneau : « NOM Prénom » de la carte d'identité (mod minenorthidentite),
     * sinon le pseudo Minecraft si le joueur n'a pas encore de carte.
     */
    public static String display(MinecraftServer s, UUID id) {
        String rp = rpName(s, id);
        return rp.isEmpty() ? name(s, id) : rp;
    }

    /** « NOM Prénom » de la carte d'identité (format administratif), ou "" sans carte. MineNorth API. */
    public static String rpName(MinecraftServer s, UUID id) {
        return fr.minenorth.api.MineNorth.identity().get(s, id).map(fr.minenorth.api.Identity::officialName).orElse("");
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

    /** Recherche par nom RP (« Prénom Nom », dans un sens ou l'autre) ou par pseudo exact, insensible à la casse. */
    @Nullable
    public static Known byName(MinecraftServer s, String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim().replaceAll("\\s+", " ");
        List<Known> all = all(s);
        for (Known k : all) {
            String rp = rpName(s, k.id());
            if (rp.isEmpty()) continue;
            String[] parts = rp.split(" ", 2);
            String reversed = parts.length == 2 ? parts[1] + " " + parts[0] : rp;
            if (rp.equalsIgnoreCase(n) || reversed.equalsIgnoreCase(n)) return k;
        }
        ServerPlayer p = s.getPlayerList().getPlayerByName(n);
        if (p != null) return new Known(p.getUUID(), p.getGameProfile().getName(), true);
        for (Known k : all) if (k.name().equalsIgnoreCase(n)) return k;
        return null;
    }
}
