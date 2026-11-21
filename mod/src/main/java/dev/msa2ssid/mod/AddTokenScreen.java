package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import dev.msa2ssid.core.Store;
import dev.msa2ssid.core.Throttle;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Adds accounts from tokens their owner pastes in, and stores the optional OAuth
 * client id — so nothing has to be edited in a JSON file.
 *
 * One token or a whole "label:token" list both go through {@link #submit}, which adds
 * them one at a time and reports how many worked. Accepted shapes: a Microsoft
 * refresh token (M.C5..., 0.A...), an existing Minecraft access token (eyJ..., an
 * SSID), or an Xbox/Microsoft access ticket (M....).
 */
public final class AddTokenScreen extends Screen {

    private static final int PANEL_WIDTH = 384;
    private static final int PANEL_HEIGHT = 196;
    private static final int PAD = 10;
    private static final int MAX_LINES = 50;

    private EditBox tokenBox;
    private EditBox clientBox;
    private volatile String status = "";
    private volatile boolean busy;

    private int px;
    private int py;
    private int pw;
    private int ph;

    public AddTokenScreen() {
        super(Component.literal("Add a token"));
    }

    private int tokenY() {
        return py + 66;
    }

    private int clientY() {
        return py + 110;
    }

    private int buttonY() {
        return py + 140;
    }

    private int[] saveBounds() {
        return new int[]{px + pw - PAD - 58, clientY(), 58, 20};
    }

    private int[] addBounds() {
        return new int[]{px + PAD, buttonY(), 110, 22};
    }

    private int[] importBounds() {
        int[] add = addBounds();
        return new int[]{add[0] + add[2] + 6, buttonY(), 128, 22};
    }

    private int[] backBounds() {
        return new int[]{px + pw - PAD - 110, buttonY(), 110, 22};
    }

    @Override
    protected void init() {
        pw = Math.min(PANEL_WIDTH, this.width - 24);
        ph = Math.min(PANEL_HEIGHT, this.height - 24);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;

        tokenBox = new EditBox(this.font, px + PAD, tokenY(), pw - 2 * PAD, 20,
                Component.literal("token"));
        tokenBox.setMaxLength(8192);
        tokenBox.setHint(Component.literal("M.C5...   eyJ...   M.C..."));
        addRenderableWidget(tokenBox);

        int[] save = saveBounds();
        clientBox = new EditBox(this.font, px + PAD, clientY(), save[0] - PAD - 8 - (px + PAD), 20,
                Component.literal("client id"));
        clientBox.setMaxLength(64);
        clientBox.setHint(Component.literal("azure application (client) id"));
        String existing = AccountManagerMod.store().clientId();
        if (existing != null) clientBox.setValue(existing);
        addRenderableWidget(clientBox);

        button(save, this::saveClientId);
        button(addBounds(), () -> submit(Auth.parseTokenList(tokenBox.getValue())));
        button(importBounds(), () -> submit(Auth.parseTokenList(
                Minecraft.getInstance().keyboardHandler.getClipboard())));
        button(backBounds(), this::onClose);
    }

    /** Invisible hit-target; the visible pill is drawn in extractRenderState. */
    private void button(int[] bounds, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(""), b -> action.run())
                .bounds(bounds[0], bounds[1], bounds[2], bounds[3]).build());
    }

    /**
     * Adds every line as an account, one at a time. Lines are paced and a 429 is
     * retried with backoff; a line that is still rate limited stops the import so the
     * rest can be pasted again once the limit clears. Other failures are counted and
     * reported, but never stop the rest.
     */
    private void submit(List<Auth.TokenLine> lines) {
        if (busy) return;
        if (lines.isEmpty()) {
            status = "no tokens found (label:token per line)";
            return;
        }
        if (lines.size() > MAX_LINES) {
            status = "too many lines (" + lines.size() + ", max " + MAX_LINES + ")";
            return;
        }

        busy = true;
        status = "Adding " + lines.size() + "...";

        CompletableFuture.runAsync(() -> {
            Store store = AccountManagerMod.store();
            String clientId = store.clientId();
            int added = 0;
            String firstFailure = null;
            String stopped = null;
            String focus = null;

            for (int i = 0; i < lines.size(); i++) {
                Auth.TokenLine line = lines.get(i);
                String who = line.label() == null ? "line " + (i + 1) : line.label();
                String progress = "Adding " + (i + 1) + " of " + lines.size() + ": ";
                status = progress + who;
                try {
                    if (i > 0) Throttle.gap();
                    Auth.TokenLogin login = Throttle.login(clientId, line.token(),
                            note -> status = progress + note);
                    Account account = Account.fromSession(login.session(), login.msRefreshToken());
                    store.put(account);
                    Heads.invalidate(account.uuid());
                    Heads.request(account);
                    focus = account.uuid();
                    added++;
                } catch (Exception e) {
                    if (Throttle.rateLimited(e)) {
                        stopped = who;
                        break;
                    }
                    if (firstFailure == null) firstFailure = who + ": " + AccountScreen.message(e);
                }
            }

            String note = save(store, added, lines.size(), firstFailure, stopped);
            busy = false;

            Minecraft mc = Minecraft.getInstance();
            String focusUuid = focus;
            mc.execute(() -> mc.setScreenAndShow(new AccountScreen(focusUuid, note)));
        });
    }

    private static String save(Store store, int added, int total, String firstFailure, String stopped) {
        try {
            store.save();
        } catch (Exception e) {
            return "Added " + added + " but could not save: " + AccountScreen.message(e);
        }
        String summary = "Added " + added + " of " + total;
        if (stopped != null) {
            return summary + " - rate limited at " + stopped
                    + ", wait a minute and import the rest";
        }
        return firstFailure == null ? summary : summary + " - first failure: " + firstFailure;
    }

    private void saveClientId() {
        Store store = AccountManagerMod.store();
        String value = clientBox.getValue() == null ? "" : clientBox.getValue().trim();
        store.setClientId(value);
        try {
            store.save();
            status = value.isEmpty() ? "client id cleared" : "client id saved";
        } catch (Exception e) {
            status = "Failed: could not save: " + AccountScreen.message(e);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.panel(g, px, py, pw, ph);
        Ui.header(g, this.font, px, py, pw, 34, "PASTE A TOKEN", "MSA / SSID", Ui.MUTED);

        // Field frames go under the boxes; the boxes draw during super.
        int[] save = saveBounds();
        Ui.field(g, px + PAD, tokenY(), pw - 2 * PAD, 20);
        Ui.field(g, px + PAD, clientY(), save[0] - PAD - 8 - (px + PAD), 20);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.text(this.font, "M.C5... refresh   eyJ... SSID   M.... ticket",
                px + PAD, py + 40, Ui.MUTED);
        g.text(this.font, "Import list reads the clipboard: label:token per line",
                px + PAD, py + 50, Ui.MUTED);
        g.text(this.font, "client id - optional, only for a registered Azure app",
                px + PAD, py + 98, Ui.MUTED);

        pill(g, mouseX, mouseY, save, "Save", false);
        pill(g, mouseX, mouseY, addBounds(), "Add", true);
        pill(g, mouseX, mouseY, importBounds(), "Import list", false);
        pill(g, mouseX, mouseY, backBounds(), "Back", false);

        if (status.isEmpty()) {
            g.text(this.font, "only use tokens for accounts you own", px + PAD, py + 170, Ui.MUTED);
        } else {
            int color = busy || status.contains("rate limited") ? Ui.WARN
                    : status.startsWith("Added") || status.endsWith("saved") ? Ui.OK : Ui.DANGER;
            g.text(this.font, status, px + PAD, py + 170, color);
        }
    }

    private void pill(GuiGraphicsExtractor g, int mouseX, int mouseY, int[] b,
                      String label, boolean primary) {
        Ui.pill(g, this.font, b[0], b[1], b[2], b[3], label,
                Ui.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]), primary);
    }
}
