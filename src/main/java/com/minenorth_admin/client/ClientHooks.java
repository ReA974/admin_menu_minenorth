package com.minenorth_admin.client;

import com.minenorth_admin.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientHooks {
    private ClientHooks() {}

    public static void handle(Net.View v) {
        Minecraft mc = Minecraft.getInstance();
        if (v.kind().equals("close")) {
            if (mc.screen instanceof AdminScreen) mc.setScreen(null);
            return;
        }
        if (mc.screen instanceof AdminScreen s) {
            s.receive(v);
        } else if (v.kind().equals("home") && v.data().getBoolean("Open")) {
            AdminScreen s = new AdminScreen();
            mc.setScreen(s);
            s.receive(v);
        }
    }
}
