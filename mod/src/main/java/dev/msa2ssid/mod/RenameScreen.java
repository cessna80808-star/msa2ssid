package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Renames the selected account through Microsoft's official endpoint.
 *
 * This changes the real account name for the account that signed in here, and it
 * uses up that account's 30-day name change, so the button states that plainly.
 */
public final class RenameScreen extends Screen {

    private static final String NAME_PATTERN = "^[A-Za-z0-9_]{3,16}$";

    private final Account account;
    private EditBox box;
    private String status = "";
    private volatile boolean busy;

    public RenameScreen(Account account) {
        super(Component.literal("Rename account"));
        this.account = account;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        box = new EditBox(this.font, cx - 100, this.height / 2 - 26, 200, 20, Component.literal("new name"));
        box.setMaxLength(16);
        box.setValue(account.name() == null ? "" : account.name());
        addRenderableWidget(box);

        addRenderableWidget(Button.builder(Component.literal("Check availability"), b -> check())
                .bounds(cx - 100, this.height / 2 + 2, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> rename())
                .bounds(cx - 100, this.height / 2 + 26, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(cx + 4, this.height / 2 + 26, 96, 20).build());
    }

    private String requested() {
        return box == null ? "" : box.getValue().trim();
    }

    private boolean valid() {
        String name = requested();
        if (!name.matches(NAME_PATTERN)) {
            status = "3-16 letters, numbers or underscores";
            return false;
        }
        return true;
    }

    private void check() {
        if (!valid() || busy) return;
        busy = true;
        status = "Checking...";
        String name = requested();
        CompletableFuture.runAsync(() -> {
            try {
                Auth.Session session = Auth.ensureSession(AccountManagerMod.store().clientId(), account);
                Auth.NameCheck result = Auth.nameAvailable(session.accessToken(), name);
                status = result.available() ? "\"" + name + "\" is available" : "Not available (" + result.status() + ")";
            } catch (Exception e) {
                AccountScreen.authFailure(account, e, s -> status = s);
            } finally {
                busy = false;
            }
        });
    }

    private void rename() {
        if (!valid() || busy) return;
        busy = true;
        status = "Renaming...";
        String name = requested();
        CompletableFuture.runAsync(() -> {
            try {
                Auth.Session session = Auth.ensureSession(AccountManagerMod.store().clientId(), account);
                Auth.NameCheck result = Auth.nameAvailable(session.accessToken(), name);
                if (!result.available()) {
                    status = "Not available (" + result.status() + ")";
                    return;
                }
                Auth.Session renamed = Auth.rename(session.accessToken(), name);
                Account updated = account.withProfile(renamed.name(), account.uuid(), account.xuid(), account.skinUrl());
                AccountManagerMod.store().put(updated);
                AccountManagerMod.store().save();
                status = "Renamed to " + renamed.name();
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreenAndShow(new AccountScreen()));
            } catch (Exception e) {
                AccountScreen.authFailure(account, e, s -> status = s);
            } finally {
                busy = false;
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Background is extracted by the caller; blurring again breaks the frame.
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        g.centeredText(this.font, "Rename " + (account.name() == null ? "account" : account.name()),
                cx, this.height / 2 - 56, 0xFFFFFFFF);
        g.centeredText(this.font, "This changes the real account name and uses its 30-day change",
                cx, this.height / 2 - 42, 0xFFFFAA00);
        g.centeredText(this.font, status, cx, this.height / 2 + 52, 0xFF9E9E9E);
    }
}
