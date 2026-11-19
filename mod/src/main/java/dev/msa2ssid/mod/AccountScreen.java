package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import dev.msa2ssid.core.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The account list, drawn as its own panel rather than vanilla widget stacks:
 * a header strip with client-id state, account cards with skin heads and session
 * state, and two rows of pill actions at the bottom.
 *
 * Rows and actions are invisible vanilla buttons purely for hit-testing; every
 * pixel is drawn here.
 */
public final class AccountScreen extends Screen {

    private static final int PANEL_WIDTH = 384;
    private static final int HEADER_H = 38;
    private static final int ROW_H = 34;
    private static final int ROW_GAP = 3;
    private static final int MAX_ROWS = 6;
    private static final int FOOTER_H = 74;
    private static final int PAD = 8;
    private static final int BTN_H = 22;

    private static final String[] ROW_A = {"Add account", "Paste token", "SSID", "Activate"};
    private static final String[] ROW_B = {"Skin", "Rename", "Remove", "Done"};
    private static final int ACTIVATE = 3;

    private final List<Button> rows = new ArrayList<>();
    private String focusUuid;
    private int selected = -1;
    private String status = "";

    private int px;
    private int py;
    private int pw;
    private int ph;
    private int rowsTop;
    private int rowsArea;

    public AccountScreen() {
        this(null);
    }

    /** @param focusUuid account to re-select after returning from a sub-screen */
    public AccountScreen(String focusUuid) {
        this(focusUuid, "");
    }

    /** @param note status line to show, e.g. the result of a batch import */
    public AccountScreen(String focusUuid, String note) {
        super(Component.literal("Accounts"));
        this.focusUuid = focusUuid;
        this.status = note == null ? "" : note;
    }

    private Store store() {
        return AccountManagerMod.store();
    }

    private int visibleRows() {
        int fits = (rowsArea + ROW_GAP) / (ROW_H + ROW_GAP);
        return Math.max(1, Math.min(MAX_ROWS, fits));
    }

    private int rowY(int i) {
        return rowsTop + i * (ROW_H + ROW_GAP);
    }

    private int footerY() {
        return py + ph - FOOTER_H;
    }

    private int[] rowABounds(int i) {
        int w = (pw - 2 * PAD - 3 * 7) / 4;
        return new int[]{px + PAD + i * (w + 7), footerY() + 16, w, BTN_H};
    }

    private int[] rowBBounds(int i) {
        int w = (pw - 2 * PAD - 3 * 6) / 4;
        return new int[]{px + PAD + i * (w + 6), footerY() + 16 + BTN_H + 6, w, BTN_H};
    }

    @Override
    protected void init() {
        pw = Math.min(PANEL_WIDTH, this.width - 24);
        int maxArea = MAX_ROWS * (ROW_H + ROW_GAP) - ROW_GAP;
        ph = Math.min(HEADER_H + 6 + maxArea + FOOTER_H, this.height - 24);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        rowsTop = py + HEADER_H + 6;
        rowsArea = ph - HEADER_H - 6 - FOOTER_H;

        rows.clear();
        List<Account> accounts = store().accounts();
        int visible = Math.min(accounts.size(), visibleRows());
        for (int i = 0; i < visible; i++) {
            final int index = i;
            rows.add(addRenderableWidget(Button.builder(Component.literal(""), b -> select(index))
                    .bounds(px + PAD, rowY(i), pw - 2 * PAD, ROW_H)
                    .build()));
        }

        for (int i = 0; i < ROW_A.length; i++) {
            final int index = i;
            int[] b = rowABounds(i);
            addRenderableWidget(Button.builder(Component.literal(""), x -> actionA(index))
                    .bounds(b[0], b[1], b[2], b[3]).build());
        }
        for (int i = 0; i < ROW_B.length; i++) {
            final int index = i;
            int[] b = rowBBounds(i);
            addRenderableWidget(Button.builder(Component.literal(""), x -> actionB(index))
                    .bounds(b[0], b[1], b[2], b[3]).build());
        }

        if (focusUuid != null) {
            for (int i = 0; i < accounts.size(); i++) {
                if (focusUuid.equals(accounts.get(i).uuid())) {
                    selected = i;
                    break;
                }
            }
            focusUuid = null;
        }
        if (selected < 0 && !accounts.isEmpty()) selected = 0;
    }

    private void select(int index) {
        selected = index;
        status = "";
        this.rebuildWidgets();
    }

    private void actionA(int i) {
        switch (i) {
            case 0 -> openSignIn();
            case 1 -> openToken();
            case 2 -> convert();
            default -> activate();
        }
    }

    private void actionB(int i) {
        switch (i) {
            case 0 -> openSkin();
            case 1 -> openRename();
            case 2 -> removeSelected();
            default -> onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Panel and header first, so the invisible hit-testing widgets sit on top.
        Ui.panel(g, px, py, pw, ph);
        boolean hasClientId = store().clientId() != null;
        Ui.header(g, this.font, px, py, pw, HEADER_H, "ACCOUNT MANAGER",
                hasClientId ? "CLIENT ID SET" : "NO CLIENT ID",
                hasClientId ? Ui.MUTED : Ui.DANGER);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        List<Account> accounts = store().accounts();
        if (accounts.isEmpty()) {
            g.centeredText(this.font, "No accounts yet", px + pw / 2, rowsTop + 34, Ui.TEXT);
            g.centeredText(this.font, "Add account signs one in, Paste token takes one you have",
                    px + pw / 2, rowsTop + 48, Ui.MUTED);
            g.centeredText(this.font, "Activate switches the running game to the selected account",
                    px + pw / 2, rowsTop + 62, Ui.MUTED);
        }

        for (int i = 0; i < rows.size() && i < accounts.size(); i++) {
            Account account = accounts.get(i);
            int y = rowY(i);
            int x = px + PAD;
            int w = pw - 2 * PAD;
            Ui.card(g, x, y, w, ROW_H, i == selected, Ui.inside(mouseX, mouseY, x, y, w, ROW_H));

            Heads.request(account);
            Identifier head = Heads.get(account);
            if (head != null) {
                Compat.blitHead(g, head, x + 8, y + 7, 20);
            } else {
                g.fill(x + 8, y + 7, x + 28, y + 27, Ui.PLACEHOLDER);
            }

            String name = account.name() == null ? "(unnamed)" : account.name();
            g.text(this.font, name, x + 36, y + 7, Ui.TEXT);
            String meta = shortId(account.uuid()) + "   "
                    + (account.msRefreshToken() != null ? "refresh token" : "session only");
            g.text(this.font, meta, x + 36, y + 19, Ui.MUTED);

            boolean active = SessionInject.isActive(account.uuid());
            boolean live = account.hasLiveSession();
            String state = active ? "active" : live ? "live" : "expired";
            g.text(this.font, state, x + w - 10 - this.font.width(state), y + 13,
                    active || live ? Ui.OK : Ui.DANGER);
        }

        for (int i = 0; i < ROW_A.length; i++) {
            int[] b = rowABounds(i);
            Ui.pill(g, this.font, b[0], b[1], b[2], b[3], ROW_A[i],
                    Ui.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]), i == ACTIVATE);
        }
        for (int i = 0; i < ROW_B.length; i++) {
            int[] b = rowBBounds(i);
            Ui.pill(g, this.font, b[0], b[1], b[2], b[3], ROW_B[i],
                    Ui.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]), false);
        }

        String note = status.isEmpty() && !hasClientId
                ? "Set a client id in Paste token to use refresh tokens"
                : status;
        if (!note.isEmpty()) {
            int color = note.startsWith("Failed") ? Ui.DANGER
                    : note.startsWith("Removed") ? Ui.WARN
                    : (!hasClientId && status.isEmpty() ? Ui.DANGER : Ui.MUTED);
            g.text(this.font, note, px + PAD, footerY() + 3, color);
        }
    }

    private static String shortId(String uuid) {
        if (uuid == null) return "";
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    // ------------------------------------------------------------------ actions

    private void openSignIn() {
        String clientId = store().clientId();
        if (clientId == null) {
            status = "Failed: set a client id in Paste token first";
            return;
        }
        this.minecraft.setScreenAndShow(new DeviceCodeScreen(clientId));
    }

    private void openToken() {
        this.minecraft.setScreenAndShow(new AddTokenScreen());
    }

    private void openSkin() {
        Account account = selectedAccount();
        if (account == null) {
            status = "Select an account first";
            return;
        }
        this.minecraft.setScreenAndShow(new SkinScreen(account));
    }

    private void openRename() {
        Account account = selectedAccount();
        if (account == null) {
            status = "Select an account first";
            return;
        }
        this.minecraft.setScreenAndShow(new RenameScreen(account));
    }

    private Account selectedAccount() {
        List<Account> accounts = store().accounts();
        return selected >= 0 && selected < accounts.size() ? accounts.get(selected) : null;
    }

    private void removeSelected() {
        Account account = selectedAccount();
        if (account == null) {
            status = "Select an account first";
            return;
        }
        store().remove(account);
        selected = -1;
        status = "Removed " + account.name();
        Heads.invalidate(account.uuid());
        persist();
        this.rebuildWidgets();
    }

    /**
     * Resolves a live session and stores it. Network call: background threads only.
     * Returns the account with its new token, expiry and any refreshed profile fields.
     */
    private Account refresh(Account account, String clientId) throws Exception {
        Auth.Session session = Auth.ensureSession(clientId, account);
        Account updated = account
                .withSession(session.accessToken(), session.expiresInSeconds())
                .withProfile(session.name() == null ? account.name() : session.name(),
                        session.uuid() == null ? account.uuid() : session.uuid(),
                        account.xuid(),
                        session.skinUrl() == null ? account.skinUrl() : session.skinUrl());
        store().put(updated);
        store().save();
        return updated;
    }

    /** Resolves a live Minecraft session token (the "SSID") and copies it. */
    private void convert() {
        Account account = selectedAccount();
        if (account == null) {
            status = "Select an account first";
            return;
        }
        status = "Refreshing session for " + account.name() + "...";
        String clientId = store().clientId();

        CompletableFuture.runAsync(() -> {
            try {
                Account updated = refresh(account, clientId);
                long minutes = Math.max(1, updated.sessionSecondsLeft() / 60);
                Minecraft.getInstance().execute(() -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(updated.mcAccessToken());
                    status = "SSID copied to clipboard (valid ~" + minutes + " min)";
                    this.rebuildWidgets();
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> authFailure(account, e, s -> {
                    status = s;
                    this.rebuildWidgets();
                }));
            }
        });
    }

    /** Makes the selected account the client's session, so the next join uses it. */
    private void activate() {
        Account account = selectedAccount();
        if (account == null) {
            status = "Select an account first";
            return;
        }
        status = "Activating " + account.name() + "...";
        String clientId = store().clientId();

        CompletableFuture.runAsync(() -> {
            try {
                Account updated = refresh(account, clientId);
                Minecraft.getInstance().execute(() -> {
                    try {
                        SessionInject.apply(updated);
                        status = "Playing as " + updated.name() + " - join or rejoin a server";
                    } catch (Exception e) {
                        status = "Failed: " + message(e);
                    }
                    this.rebuildWidgets();
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> authFailure(account, e, s -> {
                    status = s;
                    this.rebuildWidgets();
                }));
            }
        });
    }

    private void persist() {
        try {
            store().save();
        } catch (Exception e) {
            status = "Failed: could not save: " + message(e);
        }
    }

    /**
     * Reports an auth failure for a stored account. A token the services have
     * rejected is dropped from the list, because it can never work again without a
     * fresh sign-in; a network blip or a locked/banned account only reports.
     */
    static void authFailure(Account account, Exception e, Consumer<String> report) {
        if (!(e instanceof Auth.TokenRejectedException)) {
            report.accept("Failed: " + message(e));
            return;
        }
        Minecraft.getInstance().execute(() -> {
            Store store = AccountManagerMod.store();
            store.remove(account);
            String note;
            try {
                store.save();
                note = "Removed " + account.name() + " - " + message(e);
            } catch (Exception saveError) {
                note = "Removed " + account.name() + " but could not save: " + message(saveError);
            }
            Heads.invalidate(account.uuid());
            report.accept(note);
        });
    }

    static String message(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
