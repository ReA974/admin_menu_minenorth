package com.minenorth_admin.client;

import com.minenorth_admin.MineNorthAdmin;
import com.minenorth_admin.net.Net;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Touche d'ouverture du panneau (F8 par défaut, modifiable dans Options > Contrôles). Le serveur revérifie les droits. */
public final class KeyBinds {
    public static final KeyMapping OPEN = new KeyMapping("key.minenorth_admin.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, "key.categories.minenorth_admin");

    @Mod.EventBusSubscriber(modid = MineNorthAdmin.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Register {
        @SubscribeEvent
        public static void onRegister(RegisterKeyMappingsEvent e) {
            e.register(OPEN);
        }
    }

    @Mod.EventBusSubscriber(modid = MineNorthAdmin.MODID, value = Dist.CLIENT)
    public static final class Tick {
        @SubscribeEvent
        public static void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (OPEN.consumeClick()) {
                if (mc.player != null && mc.screen == null) Net.CHANNEL.sendToServer(Net.Req.of("open"));
            }
        }
    }
}
