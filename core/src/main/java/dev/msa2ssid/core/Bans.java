package dev.msa2ssid.core;

import java.util.Locale;

/** Recognizes the kick a banned Hypixel account gets, so the account can be dropped. */
public final class Bans {

    private Bans() {
    }

    /** True for hypixel.net and its subdomains, with or without a port. */
    public static boolean isHypixel(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }

    /**
     * True for a ban message: permanent, temporary, or the security block Hypixel
     * answers with. A normal kick ("You were kicked from this server") is not a ban.
     */
    public static boolean looksBanned(String kickMessage) {
        String text = kickMessage == null ? "" : kickMessage.toLowerCase(Locale.ROOT);
        if (text.contains("blocked from joining this server")) return true;
        if (!text.contains("banned")) return false;
        return text.contains("from this server") || text.contains("hypixel") || text.contains("appeal");
    }
}
