package com.minenorth_admin.server;

/**
 * Résultat d'une action : message pour le staff, réussite, et texte du journal (null = rien à journaliser).
 */
public record Result(String message, boolean ok, String log) {
    public static Result ok(String message, String log) {
        return new Result(message, true, log);
    }

    public static Result fail(String message) {
        return new Result(message, false, null);
    }
}
