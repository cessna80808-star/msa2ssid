package dev.msa2ssid.mod;

import com.mojang.blaze3d.platform.InputConstants;
import dev.msa2ssid.core.Store;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Client entry point: loads the account store, adds the keybind and the title-screen button. */
public final class AccountManagerMod implements ClientModInitializer {

    private static Store store;
    private static KeyMapping openKey;

    public static Store store() {
        return store;
    }

    @Override
    public void onInitializeClient() {
        store = Store.load(FabricLoader.getInstance().getConfigDir().resolve("msa2ssid.json"));

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.msa2ssid.accounts", Compat.keyboard(), InputConstants.KEY_RSHIFT,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                client.setScreenAndShow(new AccountScreen());
                break;
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("Accounts"),
                                b -> client.setScreenAndShow(new AccountScreen()))
                        .bounds(scaledWidth - 108, scaledHeight - 26, 100, 20)
                        .build());
            }
        });
    }
}
