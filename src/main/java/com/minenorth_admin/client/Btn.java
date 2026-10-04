package com.minenorth_admin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Bouton plat, même style que les écrans MineNorth (banque, boutiques). */
@OnlyIn(Dist.CLIENT)
public class Btn extends AbstractButton {
    public static final int CYAN = 0xFF20AAEB;
    public static final int DARK = 0xFF4A3CB4;
    public static final int PINK = 0xFFC83CF0;
    public static final int GREEN = 0xFF22A86B;
    public static final int RED = 0xFFD8434F;
    public static final int GHOST = 0;

    private final Runnable action;
    private final int color;
    private boolean selected;

    public Btn(int x, int y, int w, int h, String label, int color, Runnable action) {
        super(x, y, w, h, Component.literal(label));
        this.color = color;
        this.action = action;
    }

    public Btn enabled(boolean on) {
        this.active = on;
        return this;
    }

    /** Onglet actif : reste allumé. */
    public Btn selected(boolean on) {
        this.selected = on;
        return this;
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 35);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 35);
        int b = Math.min(255, (c & 0xFF) + 35);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        Font font = Minecraft.getInstance().font;
        int x = getX(), y = getY();
        boolean hov = isHovered() && active;
        int ty = y + (height - 8) / 2;
        Component label = getMessage();
        if (font.width(label) > width - 4) {
            label = Component.literal(font.plainSubstrByWidth(label.getString(), width - 8) + "…");
        }
        if (color == GHOST) {
            int tc = !active ? 0xFF6F7FB0 : selected || hov ? 0xFFFFFFFF : 0xFFCFE3FF;
            g.drawCenteredString(font, label, x + width / 2, ty, tc);
            if (selected || hov) g.fill(x + 2, y + height - 1, x + width - 2, y + height, selected ? CYAN : tc);
            return;
        }
        int bg = !active ? 0xFF2A2468 : selected ? lighten(color) : hov ? lighten(color) : color;
        g.fill(x, y, x + width, y + height, bg);
        if (selected) g.fill(x, y + height - 2, x + width, y + height, 0xFFFFFFFF);
        g.drawCenteredString(font, label, x + width / 2, ty, active ? 0xFFFFFFFF : 0xFF8FA8E0);
    }
}
