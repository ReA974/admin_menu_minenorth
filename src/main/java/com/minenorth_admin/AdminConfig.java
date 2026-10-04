package com.minenorth_admin;

import net.minecraftforge.common.ForgeConfigSpec;

/** Configuration serveur : world/serverconfig/minenorth_admin-server.toml */
public final class AdminConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue OWNER_OP_LEVEL;
    public static final ForgeConfigSpec.IntValue MAX_LOGS;
    public static final ForgeConfigSpec.BooleanValue LOG_FILE;
    public static final ForgeConfigSpec.BooleanValue NOTIFY_TARGET;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("acces");
        OWNER_OP_LEVEL = b.comment("Niveau d'op qui donne TOUS les droits du panneau (propriétaire), sans rôle.",
                        "Les autres joueurs n'ont accès qu'avec un rôle attribué dans l'onglet Staff.")
                .defineInRange("niveauOpProprietaire", 4, 1, 4);
        b.pop();
        b.push("journal");
        MAX_LOGS = b.comment("Nombre d'actions gardées dans le journal du panneau.")
                .defineInRange("entreesMax", 2000, 100, 20000);
        LOG_FILE = b.comment("Écrire aussi chaque action dans logs/minenorth_admin.log (dossier du serveur).")
                .define("fichier", true);
        b.pop();
        b.push("joueurs");
        NOTIFY_TARGET = b.comment("Prévenir le joueur concerné (message discret) quand le staff modifie sa banque ou ses permis.")
                .define("prevenirJoueur", true);
        b.pop();
        SPEC = b.build();
    }

    private AdminConfig() {}
}
