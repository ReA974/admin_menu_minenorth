package com.minenorth_admin.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import com.minenorth_admin.Players;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/** Sanctions : expulsion, ban définitif, ban temporaire (liste de bannissement vanilla : banned-players.json). */
public final class ModModule {
    private ModModule() {}

    @Nullable
    private static GameProfile profile(MinecraftServer s, UUID id) {
        ServerPlayer p = Players.online(s, id);
        if (p != null) return p.getGameProfile();
        if (s.getProfileCache() == null) return null;
        Optional<GameProfile> g = s.getProfileCache().get(id);
        return g.orElse(null);
    }

    public static CompoundTag view(MinecraftServer s, UUID id) {
        CompoundTag t = new CompoundTag();
        GameProfile gp = profile(s, id);
        UserBanList bans = s.getPlayerList().getBans();
        UserBanListEntry e = gp == null ? null : bans.get(gp);
        if (e != null && (e.getExpires() == null || e.getExpires().after(new Date()))) {
            t.putBoolean("Banned", true);
            t.putString("Reason", e.getReason() == null ? "" : e.getReason());
            t.putString("By", e.getSource() == null ? "" : e.getSource());
            Date exp = e.getExpires();
            t.putLong("Expires", exp == null ? 0 : exp.getTime());
        }
        return t;
    }

    private static String clean(String r) {
        r = r == null ? "" : r.replaceAll("[\\p{Cntrl}§]", "").trim();
        return r.length() > 100 ? r.substring(0, 100) : r;
    }

    private static String duration(long minutes) {
        if (minutes % 1440 == 0) return (minutes / 1440) + " j";
        if (minutes % 60 == 0) return (minutes / 60) + " h";
        return minutes + " min";
    }

    public static Result act(ServerPlayer actor, UUID id, String action, String reasonRaw, long minutes) {
        MinecraftServer s = actor.server;
        if (id.equals(actor.getUUID())) return Result.fail("Tu ne peux pas te sanctionner toi-même.");
        String reason = clean(reasonRaw);
        String name = Players.display(s, id);
        ServerPlayer online = Players.online(s, id);
        UserBanList bans = s.getPlayerList().getBans();
        switch (action) {
            case "mod.kick" -> {
                if (online == null) return Result.fail("Le joueur doit être connecté.");
                online.connection.disconnect(Component.literal("Tu as été expulsé du serveur." + (reason.isEmpty() ? "" : "\nRaison : " + reason)));
                return Result.ok(name + " expulsé.", "expulse le joueur" + (reason.isEmpty() ? "" : " (" + reason + ")"));
            }
            case "mod.ban", "mod.tempban" -> {
                boolean temp = action.equals("mod.tempban");
                if (temp && (minutes <= 0 || minutes > 60L * 24 * 3650)) return Result.fail("Durée invalide.");
                GameProfile gp = profile(s, id);
                if (gp == null) return Result.fail("Profil introuvable (le joueur doit s'être connecté au moins une fois).");
                Date now = new Date();
                Date exp = temp ? new Date(now.getTime() + minutes * 60_000L) : null;
                String why = reason.isEmpty() ? "Banni par un membre du staff." : reason;
                bans.add(new UserBanListEntry(gp, now, actor.getGameProfile().getName(), exp, why));
                if (online != null) {
                    online.connection.disconnect(Component.literal((temp ? "Tu es banni pour " + duration(minutes) + "." : "Tu es banni du serveur.") + "\nRaison : " + why));
                }
                String what = temp ? "ban " + duration(minutes) : "ban définitif";
                return Result.ok(name + " : " + what + ".", what + " du joueur (" + why + ")");
            }
            case "mod.unban" -> {
                GameProfile gp = profile(s, id);
                if (gp == null || !bans.isBanned(gp)) return Result.fail("Ce joueur n'est pas banni.");
                bans.remove(gp);
                return Result.ok(name + " débanni.", "débannit le joueur");
            }
            default -> {
                return Result.fail("Action inconnue.");
            }
        }
    }
}
