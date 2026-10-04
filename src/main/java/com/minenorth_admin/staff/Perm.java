package com.minenorth_admin.staff;

/** Permissions du panneau. Un rôle en accorde une liste ; le propriétaire (op 4) les a toutes. */
public enum Perm {
    PANEL("Accès au panneau"),
    BANK_VIEW("Banque : voir"),
    BANK_EDIT("Banque : modifier"),
    PERMIS_VIEW("Permis : voir"),
    PERMIS_EDIT("Permis : modifier"),
    GARAGE_VIEW("Garage : voir"),
    GARAGE_EDIT("Garage : modifier"),
    INV_VIEW("Inventaire : voir"),
    INV_EDIT("Inventaire : modifier"),
    TELEPORT("Téléportation"),
    LOGS("Journal"),
    STAFF("Gérer le staff"),
    DELETE("Supprimer un joueur");

    public final String label;

    Perm(String label) {
        this.label = label;
    }

    public static Perm byName(String s) {
        try {
            return valueOf(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
