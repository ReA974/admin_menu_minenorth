package com.minenorth_admin.server;

import net.minecraftforge.fml.ModList;

/** Mods MineNorth présents : chaque onglet du panneau n'existe que si son mod est installé. */
public final class Mods {
    private Mods() {}

    public static boolean bank() {
        return ModList.get().isLoaded("minenorth_eurobank");
    }

    public static boolean permis() {
        return ModList.get().isLoaded("minenorth_permis");
    }

    public static boolean garage() {
        return ModList.get().isLoaded("minenorth_rp_vehicles");
    }
}
