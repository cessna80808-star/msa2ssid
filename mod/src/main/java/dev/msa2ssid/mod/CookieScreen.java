package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import dev.msa2ssid.core.Store;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Signs an account in from a login.live.com browser cookie, for an owner who is
 * already signed in on their browser and would rather not type a device code.
 *
 * The cookie answers the launcher's OAuth authorize request, and the code it
 * redirects back with becomes a normal token pair — so the account is stored with a
 * refresh token and renews itself like a device-code sign-in. Nothing leaves the
 * machine except the requests to login.live.com and the Xbox/Mojang services.
 */
public final class CookieScreen extends Screen {

    private static final int PANEL_WIDTH = 384;
    private static final int PANEL_HEIGHT = 196;
    private static final int PAD = 10;
    private static final int MAX_COOKIE_LENGTH = 8192;

    private EditBox cookieBox;
    private volatile String status = "";
    private volatile boolean busy;

    private int px;
    private int py;
    private int pw;
    private int ph;

    public CookieScreen() {
        super(Component.literal("Sign in with a cookie"));
    }

    private int fieldY() {
        return py + 66;
    }

    private int buttonY() {
        return py + 140;
    }

    private int[] pasteBounds() {
        return new int[]{px + PAD, buttonY(), 88, 22};
    }

    private int[] signInBounds() {
        int[] paste = pasteBounds();
        return new int[]{paste[0] + paste[2] + 6, buttonY(), 110, 22};
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

        cookieBox = new EditBox(this.font, px + PAD, fieldY(), pw - 2 * PAD, 20,
                Component.literal("cookie"));
        cookieBox.setMaxLength(MAX_COOKIE_LENGTH);
        cookieBox.setHint(Component.literal("MSPAuth=...; MSPProf=..."));
        addRenderableWidget(cookieBox);

        button(pasteBounds(), this::pasteClipboard);
        button(signInBounds(), this::signIn);
        button(backBounds(), this::onClose);
    }

    /** Invisible hit-target; the visible pill is drawn in extractRenderState. */
    private void button(int[] bounds, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(""), b -> action.run())
                .bounds(bounds[0], bounds[1], bounds[2], bounds[3]).build());
    }

    private void pasteClipboard() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) {
            status = "clipboard is empty";
            return;
        }
        cookieBox.setValue(text.trim());
        status = "cookie pasted - press Sign in";
    }

    /**
     * Runs the cookie exchange and stores the account. Network call: background
     * thread only, with the result handed back to the render thread.
     */
    private void signIn() {
        if (busy) return;
        String raw = cookieBox.getValue();
        if (raw == null || raw.trim().isEmpty()) {
            status = "paste the cookie first";
            return;
        }

        busy = true;
        status = "Signing in...";

        CompletableFuture.runAsync(() -> {
            Store store = AccountManagerMod.store();
            try {
                Auth.TokenLogin login = Auth.loginWithCookie(raw);
                Account account = Account.fromSession(login.session(), login.msRefreshToken());
                store.put(account);
                store.save();
                Heads.invalidate(account.uuid());
                Heads.request(account);

                busy = false;
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> mc.setScreenAndShow(
                        new AccountScreen(account.uuid(), "Added " + account.name() + " from a cookie")));
            } catch (Exception e) {
                busy = false;
                status = "Failed: " + AccountScreen.message(e);
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Ui.panel(g, px, py, pw, ph);
        Ui.header(g, this.font, px, py, pw, 34, "SIGN IN WITH COOKIE", "LIVE.COM", Ui.MUTED);

        // The field frame goes under the box; the box draws during super.
        Ui.field(g, px + PAD, fieldY(), pw - 2 * PAD, 20);
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.text(this.font, "1. sign in at login.live.com in a browser",
                px + PAD, py + 40, Ui.MUTED);
        g.text(this.font, "2. copy its Cookie request header, then paste it here",
                px + PAD, py + 50, Ui.MUTED);
        g.text(this.font, "passkey-only accounts cannot sign in this way",
                px + PAD, py + 98, Ui.MUTED);

        pill(g, mouseX, mouseY, pasteBounds(), "Paste", false);
        pill(g, mouseX, mouseY, signInBounds(), "Sign in", true);
        pill(g, mouseX, mouseY, backBounds(), "Back", false);

        if (status.isEmpty()) {
            g.text(this.font, "a cookie is a session: only use your own accounts",
                    px + PAD, py + 170, Ui.MUTED);
        } else {
            int color = busy ? Ui.WARN
                    : status.startsWith("Failed") || status.equals("paste the cookie first") ? Ui.DANGER
                    : Ui.OK;
            g.text(this.font, status, px + PAD, py + 170, color);
        }
    }

    private void pill(GuiGraphicsExtractor g, int mouseX, int mouseY, int[] b,
                      String label, boolean primary) {
        Ui.pill(g, this.font, b[0], b[1], b[2], b[3], label,
                Ui.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]), primary);
    }
}
