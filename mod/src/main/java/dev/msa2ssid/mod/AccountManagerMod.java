package dev.msa2ssid.mod;

import com.mojang.blaze3d.platform.InputConstants;
import dev.msa2ssid.core.Store;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/** Client entry point: loads the account store, adds the keybind and the title-screen button. */
public final class AccountManagerMod implements ClientModInitializer {

    private static Store store;
    private static KeyMapping openKey;
    private static volatile String pendingNote;

    public static Store store() {
        return store;
    }

    /** A status line for the next time the account list opens (e.g. a ban removal). */
    static void note(String text) {
        pendingNote = text;
    }

    static String takeNote() {
        String note = pendingNote;
        pendingNote = null;
        return note;
    }

    @Override
    public void onInitializeClient() {
        store = Store.load(FabricLoader.getInstance().getConfigDir().resolve("msa2ssid.json"));

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.msa2ssid.accounts", Compat.keyboard(), InputConstants.KEY_RSHIFT,
                KeyMapping.Category.MISC));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> BanWatch.joined(client));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                client.setScreenAndShow(new AccountScreen());
                break;
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            BanWatch.screen(screen);
            if (screen instanceof TitleScreen) {
                accountsButton(client, screen, scaledWidth - 108, scaledHeight - 26, 100);
            } else if (screen instanceof JoinMultiplayerScreen) {
                // The server list starts below the header band, whose left side is empty,
                // so a small button sits there without covering any vanilla widget.
                accountsButton(client, screen, 8, 8, 76);
            }
        });
    }

    /** A button that opens the account list, placed by the caller for that screen. */
    private static void accountsButton(Minecraft client, Screen screen, int x, int y, int width) {
        Screens.getWidgets(screen).add(Button.builder(Component.literal("Accounts"),
                        b -> client.setScreenAndShow(new AccountScreen()))
                .bounds(x, y, width, 20)
                .build());
    }
}
