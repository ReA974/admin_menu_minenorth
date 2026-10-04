package com.minenorth_admin;

import com.minenorth_admin.staff.StaffData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Journal des actions du staff : dans le panneau (StaffData) + fichier logs/minenorth_admin.log. */
public final class AuditLog {
    private AuditLog() {}

    public static String date(long ms) {
        return new SimpleDateFormat("dd/MM HH:mm").format(new Date(ms));
    }

    public static void log(ServerPlayer actor, String target, String module, String text) {
        MinecraftServer s = actor.server;
        long now = System.currentTimeMillis();
        String a = actor.getGameProfile().getName();
        StaffData.get(s).log(new StaffData.LogEntry(now, a, target == null ? "" : target, module, text), AdminConfig.MAX_LOGS.get());
        MineNorthAdmin.LOG.info("[Admin] {} -> {} [{}] {}", a, target, module, text);
        if (!AdminConfig.LOG_FILE.get()) return;
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(now))
                + " | " + a + " | " + (target == null ? "-" : target) + " | " + module + " | " + text + System.lineSeparator();
        try {
            Path f = s.getServerDirectory().toPath().resolve("logs").resolve("minenorth_admin.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MineNorthAdmin.LOG.warn("[Admin] Impossible d'écrire le journal", e);
        }
    }
}
