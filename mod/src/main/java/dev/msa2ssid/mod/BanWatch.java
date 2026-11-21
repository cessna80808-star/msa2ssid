package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Bans;
import dev.msa2ssid.core.Store;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * Drops an account the moment Hypixel kicks it for a ban, so a dead alt does not
 * stay in the list. The disconnect event carries no reason, so this watches the
 * reconnect screen instead. Only the account the client is actually signed in as
 * is removed, and only when the server joined was Hypixel: another server's ban
 * screen must not empty the list.
 */
final class BanWatch {

    /** The server being joined; a disconnect screen itself carries no address. */
    private static volatile String server;

    private BanWatch() {
    }

    /** Remembers which server the client joined. */
    static void joined(Minecraft client) {
        ServerData data = client.getCurrentServer();
        server = data == null ? null : data.ip;
    }

    /** Called for every screen the client opens. */
    static void screen(Screen screen) {
        if (!(screen instanceof DisconnectedScreen)) return;

        // A play-phase kick still has the joined server recorded; a refusal during
        // the login handshake has no JOIN event, so read the live server data.
        ServerData current = Minecraft.getInstance().getCurrentServer();
        String address = current != null ? current.ip : server;
        if (!Bans.isHypixel(address)) return;

        Component narration = screen.getNarrationMessage();
        String reason = narration == null ? "" : narration.getString();
        if (!Bans.looksBanned(reason)) return;

        server = null;
        drop(reason);
    }

    private static void drop(String reason) {
        Minecraft client = Minecraft.getInstance();
        User user = client.getUser();
        if (user == null || user.getProfileId() == null) return;

        Store store = AccountManagerMod.store();
        Account account = null;
        for (Account candidate : store.accounts()) {
            if (Account.sameUuid(candidate.uuid(), user.getProfileId().toString())) {
                account = candidate;
                break;
            }
        }
        if (account == null) return;

        store.remove(account);
        Heads.invalidate(account.uuid());
        String note;
        try {
            store.save();
            note = "Removed " + account.name() + " - banned on Hypixel";
        } catch (Exception e) {
            note = "Removed " + account.name() + " but could not save: " + AccountScreen.message(e);
        }
        AccountManagerMod.note(note);
        System.err.println("[msa2ssid] " + note
                + " (" + reason.replaceAll("\\s+", " ").strip() + ")");
    }
}
