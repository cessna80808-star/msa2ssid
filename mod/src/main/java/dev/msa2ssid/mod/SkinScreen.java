package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import dev.msa2ssid.core.Skins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Changes the selected account's real skin through Minecraft services, so the new
 * skin is what other players (and this client) load from Mojang - not a local
 * override.
 *
 * Accepts a local PNG path or an http(s) URL. The URL form is downloaded and then
 * uploaded as bytes because Mojang's firewall rejects the JSON url payload.
 */
public final class SkinScreen extends Screen {

    private final Account account;
    private EditBox input;
    private String variant = "classic";
    private String status = "";
    private volatile boolean busy;

    public SkinScreen(Account account) {
        super(Component.literal("Skin"));
        this.account = account;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        input = new EditBox(this.font, cx - 130, cy - 46, 260, 20,
                Component.literal("skin path or URL"));
        input.setMaxLength(512);
        input.setHint(Component.literal("skin.png or https://..."));
        addRenderableWidget(input);

        addRenderableWidget(Button.builder(Component.literal("Model: " + label()), b -> {
            variant = variant.equals("classic") ? "slim" : "classic";
            b.setMessage(Component.literal("Model: " + label()));
        }).bounds(cx - 130, cy - 20, 126, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Upload"), b -> upload())
                .bounds(cx + 4, cy - 20, 126, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> reset())
                .bounds(cx - 130, cy + 4, 82, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Library..."), b -> openLibrary())
                .bounds(cx - 42, cy + 4, 82, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(cx + 46, cy + 4, 82, 20).build());
    }

    /** Opens the skin library, which applies the chosen skin for this account. */
    private void openLibrary() {
        this.minecraft.setScreenAndShow(new SkinLibraryScreen(account, variant));
    }

    private String label() {
        return variant.equals("slim") ? "Slim (Alex)" : "Classic (Steve)";
    }

    private void upload() {
        if (busy) return;
        String value = input.getValue().trim();
        if (value.isEmpty()) {
            status = "Enter a PNG path or an https URL";
            return;
        }
        busy = true;
        status = "Uploading...";
        String clientId = AccountManagerMod.store().clientId();
        String model = variant;

        CompletableFuture.runAsync(() -> {
            try {
                Auth.Session session = Auth.ensureSession(clientId, account);
                Auth.Session updated;
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    updated = Auth.uploadSkinFromUrl(session.accessToken(), value, model);
                } else {
                    byte[] png = Files.readAllBytes(resolve(value));
                    Skins.validateSkin(png);
                    updated = Auth.uploadSkin(session.accessToken(), png, model);
                }
                saveSkin(updated);
                status = "Skin updated - it is live on the account now";
            } catch (Exception e) {
                AccountScreen.authFailure(account, e, s -> status = s);
            } finally {
                busy = false;
            }
        });
    }

    private void reset() {
        if (busy) return;
        busy = true;
        status = "Resetting...";
        String clientId = AccountManagerMod.store().clientId();

        CompletableFuture.runAsync(() -> {
            try {
                Auth.Session session = Auth.ensureSession(clientId, account);
                saveSkin(Auth.resetSkin(session.accessToken()));
                status = "Back to the default skin";
            } catch (Exception e) {
                AccountScreen.authFailure(account, e, s -> status = s);
            } finally {
                busy = false;
            }
        });
    }

    private void saveSkin(Auth.Session updated) throws Exception {
        Account fresh = account.withProfile(
                updated.name() == null ? account.name() : updated.name(),
                updated.uuid() == null ? account.uuid() : updated.uuid(),
                account.xuid(),
                updated.skinUrl());
        AccountManagerMod.store().put(fresh);
        AccountManagerMod.store().save();
        Heads.invalidate(account.uuid());
        Heads.request(fresh);
    }

    /** Resolves an absolute path, then a bare filename in the config skin folder. */
    private Path resolve(String value) throws IOException {
        String expanded = value.startsWith("~")
                ? System.getProperty("user.home") + value.substring(1)
                : value;
        Path direct = Path.of(expanded);
        if (Files.isRegularFile(direct)) return direct;

        Path configDir = FabricLoader.getInstance().getConfigDir();
        for (Path candidate : new Path[]{
                configDir.resolve("msa2ssid-skins").resolve(value),
                configDir.resolve(value)}) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IOException("file not found: " + value
                + " (put it in config/msa2ssid-skins/ or give a full path)");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Background is extracted by the caller; blurring again breaks the frame.
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int cy = this.height / 2;
        g.centeredText(this.font, "Skin for " + (account.name() == null ? "account" : account.name()),
                cx, cy - 74, 0xFFFFFFFF);
        g.centeredText(this.font, "Uploads to the account itself, so other players see it",
                cx, cy - 62, 0xFF9E9E9E);
        g.centeredText(this.font, status, cx, cy + 34, busy ? 0xFFFFFF55 : 0xFF9E9E9E);
    }
}
