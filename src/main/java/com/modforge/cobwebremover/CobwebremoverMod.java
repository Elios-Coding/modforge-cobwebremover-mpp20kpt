package com.modforge.cobwebremover;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side implementation.
 *
 * Note: true “/cob” commands are server-side and not registered from a client-only mod.
 * This mod provides a keybind + small menu button that performs the requested action
 * on the client world (singleplayer or servers that allow client-side block changes).
 */
public class CobwebremoverMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("cobwebremover");

    public static KeyBinding OPEN_MENU_KEY;
    public static KeyBinding REMOVE_COBWEBS_KEY;

    @Override
    public void onInitializeClient() {
        try {
            // 1.21+ KeyBinding constructor takes a Text category, not a String.
            // Using a translatable text keeps compatibility with language files.
            final Text category = Text.translatable("category.cobwebremover.general");

            OPEN_MENU_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.cobwebremover.open_menu",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_KP_MULTIPLY,
                    category
            ));

            REMOVE_COBWEBS_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.cobwebremover.remove_cobwebs",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_SLASH,
                    category
            ));

            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                try {
                    if (client == null || client.player == null) return;

                    while (OPEN_MENU_KEY.wasPressed()) {
                        client.setScreen(new CobwebremoverModScreen());
                    }

                    while (REMOVE_COBWEBS_KEY.wasPressed()) {
                        removeCobwebsInLoadedArea(client);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Client tick handler failed", t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize Cobwebremover client mod", t);
        }
    }

    static void removeCobwebsInLoadedArea(MinecraftClient client) {
        try {
            if (client == null || client.world == null || client.player == null) return;

            // Use client view distance to approximate “render distance”.
            int viewDistanceChunks;
            try {
                viewDistanceChunks = client.options.getViewDistance().getValue();
            } catch (Throwable t) {
                // Fallback if options API differs.
                viewDistanceChunks = 8;
            }
            if (viewDistanceChunks < 2) viewDistanceChunks = 2;

            // Scan a cube of blocks covering the loaded chunk radius.
            // This is intentionally conservative and only touches the *client world*.
            final int radiusBlocks = Math.max(16, viewDistanceChunks * 16);

            BlockPos center = client.player.getBlockPos();
            int minX = center.getX() - radiusBlocks;
            int maxX = center.getX() + radiusBlocks;
            int minY = Math.max(-64, center.getY() - 64);
            int maxY = Math.min(320, center.getY() + 64);
            int minZ = center.getZ() - radiusBlocks;
            int maxZ = center.getZ() + radiusBlocks;

            int removed = 0;

            // Avoid excessive allocations by reusing a mutable BlockPos.
            BlockPos.Mutable pos = new BlockPos.Mutable();

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        pos.set(x, y, z);

                        // Only act on already-loaded positions.
                        // This avoids chunk loads and keeps the scan bounded to render/loaded area.
                        if (!client.world.isChunkLoaded(pos)) continue;

                        if (client.world.getBlockState(pos).isOf(Blocks.COBWEB)) {
                            // Replace cobweb with air in the client world.
                            // In multiplayer, this will NOT be authoritative.
                            boolean ok;
                            try {
                                ok = client.world.setBlockState(pos, Blocks.AIR.getDefaultState());
                            } catch (Throwable t) {
                                ok = false;
                            }
                            if (ok) removed++;
                        }
                    }
                }
            }

            client.player.sendMessage(Text.literal("Cobweb Remover: removed " + removed + " cobweb(s) (client-side)."), false);
        } catch (Throwable t) {
            LOGGER.error("Failed to remove cobwebs", t);
            try {
                if (client != null && client.player != null) {
                    client.player.sendMessage(Text.literal("Cobweb Remover: error while removing cobwebs (see log)."), false);
                }
            } catch (Throwable t2) {
                LOGGER.error("Also failed to notify player", t2);
            }
        }
    }
}

class CobwebremoverModScreen extends Screen {
    CobwebremoverModScreen() {
        super(Text.literal("Cobweb Remover"));
    }

    @Override
    protected void init() {
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Remove Cobwebs"), btn -> {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                CobwebremoverMod.removeCobwebsInLoadedArea(mc);
                try {
                    if (mc != null && mc.worldRenderer != null) mc.worldRenderer.reload();
                } catch (Throwable t) {
                    // Renderer reload API can differ; ignore.
                }
            } catch (Throwable t) {
                LoggerFactory.getLogger("cobwebremover").error("Remove Cobwebs button failed", t);
            }
        }).dimensions(8, 8, 140, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> {
            try {
                this.close();
            } catch (Throwable t) {
                LoggerFactory.getLogger("cobwebremover").error("Close button failed", t);
            }
        }).dimensions(8, 32, 60, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawTextWithShadow(this.textRenderer, this.title,
                this.width / 2 - this.textRenderer.getWidth(this.title) / 2, 60, 0xFFFFFF);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Press '/' to run without opening this menu."),
                8, 90, 0xA0A0A0);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Note: true /cob requires a server-side mod."),
                8, 104, 0xA0A0A0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
