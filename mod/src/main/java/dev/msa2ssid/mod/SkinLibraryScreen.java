package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import dev.msa2ssid.core.SkinLibrary;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Browses the skin library and applies the chosen skin to the selected account.
 *
 * The library is the folder {@code config/msa2ssid-skins/}: drop PNGs in (or add one
 * by URL here) and it shows up as a clickable head preview.
 */
public final class SkinLibraryScreen extends Screen {

    private static final int CELL = 44;
    private static final int COLS = 5;
    private static final int ROWS = 4;
    private static final int PANEL_WIDTH = 320;

    private final Account account;
    private final String variant;
    private final SkinLibrary library;
    private final List<Button> cells = new ArrayList<>();

    private volatile List<SkinLibrary.Entry> entries = List.of();
    private boolean loaded;
    private int selected = -1;
    private String status = "";
    private volatile boolean busy;
    private EditBox urlBox;

    public SkinLibraryScreen(Account account, String variant) {
        super(Component.literal("Skin library"));
        this.account = account;
        this.variant = variant;
        this.library = new SkinLibrary(
                FabricLoader.getInstance().getConfigDir().resolve("msa2ssid-skins"));
    }

    private int left() {
        return (this.width - PANEL_WIDTH) / 2;
    }

    private int gridWidth() {
        return COLS * CELL - (CELL - 40);
    }

    @Override
    protected void init() {
        cells.clear();
        if (!loaded) load();

        int gx = (this.width - gridWidth()) / 2;
        int gy = 62;
        for (int i = 0; i < Math.min(entries.size(), COLS * ROWS); i++) {
            final int index = i;
            int x = gx + (i % COLS) * CELL;
            int y = gy + (i / COLS) * CELL;
            Button cell = Button.builder(Component.literal(""), b -> select(index))
                    .bounds(x, y, 40, 40)
                    .build();
            addRenderableWidget(cell);
            cells.add(cell);
        }

        int bottom = this.height - 52;
        urlBox = new EditBox(this.font, left(), bottom, 200, 20, Component.literal("skin URL"));
        urlBox.setMaxLength(512);
        urlBox.setHint(Component.literal("https://... to add a skin"));
        addRenderableWidget(urlBox);

        addRenderableWidget(Button.builder(Component.literal("Add"), b -> addFromUrl())
                .bounds(left() + 204, bottom, 116, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
                .bounds(left(), bottom + 24, 104, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"), b -> reload())
                .bounds(left() + 108, bottom + 24, 104, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(left() + 216, bottom + 24, 104, 20).build());
    }

    private void load() {
        loaded = true;
        CompletableFuture.runAsync(() -> {
            try {
                entries = library.load();
            } catch (Exception e) {
                status = "Could not read the library: " + AccountScreen.message(e);
            }
            Minecraft.getInstance().execute(this::rebuildWidgets);
        });
    }

    private void reload() {
        loaded = false;
        entries = List.of();
        selected = -1;
        status = "Reloaded";
        this.rebuildWidgets();
    }

    private void select(int index) {
        selected = index;
        status = index < entries.size() ? entries.get(index).name() : "";
    }

    private void addFromUrl() {
        if (busy) return;
        String url = urlBox.getValue().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status = "Enter an http(s) URL";
            return;
        }
        busy = true;
        status = "Downloading...";
        String name = url.substring(url.lastIndexOf('/') + 1);
        CompletableFuture.runAsync(() -> {
            try {
                library.addFromUrl(name.isEmpty() ? "skin" : name, url);
                entries = library.load();
                status = "Added to the library";
            } catch (Exception e) {
                status = "Failed: " + AccountScreen.message(e);
            } finally {
                busy = false;
            }
            Minecraft.getInstance().execute(this::rebuildWidgets);
        });
    }

    private void apply() {
        if (busy) return;
        if (selected < 0 || selected >= entries.size()) {
            status = "Pick a skin first";
            return;
        }
        busy = true;
        status = "Applying " + entries.get(selected).name() + "...";
        SkinLibrary.Entry entry = entries.get(selected);
        String clientId = AccountManagerMod.store().clientId();

        CompletableFuture.runAsync(() -> {
            try {
                byte[] png = Files.readAllBytes(entry.file());
                Auth.Session session = Auth.ensureSession(clientId, account);
                Auth.Session updated = Auth.uploadSkin(session.accessToken(), png, variant);

                Account fresh = account.withProfile(
                        updated.name() == null ? account.name() : updated.name(),
                        updated.uuid() == null ? account.uuid() : updated.uuid(),
                        account.xuid(),
                        updated.skinUrl());
                AccountManagerMod.store().put(fresh);
                AccountManagerMod.store().save();
                Heads.invalidate(account.uuid());
                Heads.request(fresh);

                status = "Applied " + entry.name();
                Minecraft.getInstance().execute(() ->
                        Minecraft.getInstance().setScreenAndShow(new AccountScreen(fresh.uuid())));
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

        int gx = (this.width - gridWidth()) / 2;
        int gy = 62;

        g.centeredText(this.font, "Skin library", this.width / 2, 22, 0xFFFFFFFF);
        g.centeredText(this.font, variant.equals("slim") ? "uploading as Slim" : "uploading as Classic",
                this.width / 2, 40, 0xFF9E9E9E);

        for (int i = 0; i < cells.size() && i < entries.size(); i++) {
            int x = gx + (i % COLS) * CELL;
            int y = gy + (i / COLS) * CELL;
            SkinLibrary.Entry entry = entries.get(i);
            Identifier texture = Heads.preview(entry.name() + ":" + entry.headPng().length, entry.headPng());
            if (texture != null) {
                Compat.blitHead(g, texture, x + 4, y + 4, 32);
            }
            if (i == selected) {
                g.outline(x - 1, y - 1, 42, 42, 0xFFFFFFFF);
            }
        }

        if (entries.isEmpty()) {
            g.centeredText(this.font, "Empty library: drop 64x64 PNGs into", this.width / 2, gy + 20, 0xFF9E9E9E);
            g.centeredText(this.font, "config/msa2ssid-skins/ or add one by URL",
                    this.width / 2, gy + 32, 0xFF9E9E9E);
        }

        g.text(this.font, status, left(), this.height - 76, busy ? 0xFFFFFF55 : 0xFF9E9E9E);
    }
}
