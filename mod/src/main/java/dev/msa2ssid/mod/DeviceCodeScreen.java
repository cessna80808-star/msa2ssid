package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import dev.msa2ssid.core.Store;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Device-code sign-in: shows the code, waits for the account's owner to approve it
 * at microsoft.com/link, then stores that account.
 */
public final class DeviceCodeScreen extends Screen {

    private final String clientId;
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile String status = "Requesting a sign-in code...";
    private volatile String userCode;
    private volatile String uri;
    private volatile long expiresAt;

    public DeviceCodeScreen(String clientId) {
        super(Component.literal("Add account"));
        this.clientId = clientId;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Copy code"), b -> copyCode())
                .bounds(this.width / 2 - 100, this.height / 2 + 26, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            cancelled = true;
            onClose();
        }).bounds(this.width / 2 + 4, this.height / 2 + 26, 96, 20).build());

        if (userCode == null) start();
    }

    private void start() {
        CompletableFuture.runAsync(() -> {
            try {
                Auth.DeviceCode code = Auth.startDeviceCode(clientId);
                userCode = code.userCode();
                uri = code.verificationUri();
                expiresAt = code.expiresAtEpochSec();
                status = "Sign in at " + uri + " with the code above";

                Auth.MsTokens tokens = Auth.awaitDeviceCode(clientId, code, s -> status = s);
                status = "Signed in, loading Minecraft profile...";
                Auth.Session session = Auth.chain(tokens.accessToken());

                Store store = AccountManagerMod.store();
                Account account = Account.fromSession(session, tokens.refreshToken());
                store.put(account);
                store.save();

                finished = true;
                Minecraft.getInstance().execute(() -> Minecraft.getInstance()
                        .setScreenAndShow(new AccountScreen()));
            } catch (Exception e) {
                status = "Failed: " + AccountScreen.message(e);
                userCode = null;
            }
        });
    }

    private void copyCode() {
        if (userCode != null) Minecraft.getInstance().keyboardHandler.setClipboard(userCode);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Background is extracted by the caller; blurring again breaks the frame.
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;

        g.centeredText(this.font, "Add account", cx, cy - 56, 0xFFFFFFFF);
        if (userCode != null && !finished) {
            g.centeredText(this.font, userCode, cx, cy - 20, 0xFFFFFF55);
            long left = expiresAt - Instant.now().getEpochSecond();
            g.centeredText(this.font, "code expires in " + Math.max(0, left) + "s", cx, cy + 2, 0xFF9E9E9E);
        }
        g.centeredText(this.font, status, cx, cy + 52, 0xFF9E9E9E);
    }

    @Override
    public void onClose() {
        cancelled = true;
        super.onClose();
    }
}
