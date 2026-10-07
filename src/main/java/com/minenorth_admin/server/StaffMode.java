package com.minenorth_admin.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Mode staff : invisible (sans particules) + créatif. L'état est stocké dans les données persistantes du joueur
 * (survit à la déconnexion, à la mort et au milk). Quitter le mode restaure le mode de jeu d'avant.
 */
public final class StaffMode {
    private static final String ON = "mnadmin_staffmode", PREV = "mnadmin_prevgm";

    private static CompoundTag data(ServerPlayer p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    public static boolean is(ServerPlayer p) {
        return data(p).getBoolean(ON);
    }

    private static void applyVanish(ServerPlayer p) {
        // ambiant=false, particules=false, icône=false : aucune bulle autour du joueur
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
    }

    /** @return true si le mode est maintenant actif. */
    public static boolean toggle(ServerPlayer p) {
        CompoundTag d = data(p);
        if (d.getBoolean(ON)) {
            d.putBoolean(ON, false);
            p.removeEffect(MobEffects.INVISIBILITY);
            GameType prev = GameType.byId(d.contains(PREV) ? d.getInt(PREV) : GameType.SURVIVAL.getId());
            p.setGameMode(prev == GameType.SPECTATOR ? GameType.SURVIVAL : prev);
            d.remove(PREV);
            return false;
        }
        GameType cur = p.gameMode.getGameModeForPlayer();
        d.putInt(PREV, cur == GameType.CREATIVE ? GameType.SURVIVAL.getId() : cur.getId());
        d.putBoolean(ON, true);
        p.setGameMode(GameType.CREATIVE);
        applyVanish(p);
        return true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p) || p.tickCount % 40 != 0) return;
        if (is(p) && !p.hasEffect(MobEffects.INVISIBILITY)) applyVanish(p);
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && is(p)) {
            applyVanish(p);
            p.setGameMode(GameType.CREATIVE);
        }
    }
}
