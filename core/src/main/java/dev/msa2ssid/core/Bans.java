package dev.msa2ssid.core;

import java.util.Locale;

/** Recognizes the kick a banned account gets, so the account can be dropped. */
public final class Bans {

    private Bans() {
    }

    /**
     * Phrases that are a ban on their own. Hypixel's kick for a banned account is not one
     * fixed sentence, so this covers the permanent, temporary and security variants.
     */
    private static final String[] BAN_PHRASES = {
            "blocked from joining this server",   // security ban
            "security ban",
            "permanently banned",
            "temporarily banned",
            "banned from this server",
            "banned from hypixel",
            "banned from the hypixel",
            "you are banned",
            "you have been banned",
            "you were banned",
            "your account is banned",
            "your account has been banned",
            "this account is banned",
            "this account has been banned",
            "banned by an administrator",
            "ban id",
    };

    /** True for hypixel.net and its subdomains, with or without a port. */
    public static boolean isHypixel(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }

    /** The message as plain lowercase text: colours removed, whitespace collapsed. */
    public static String normalize(String message) {
        if (message == null) return "";
        return stripColours(message).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * True for a ban message: permanent, temporary, or the security block Hypixel
     * answers with. A normal kick ("You were kicked from this server") is not a ban.
     */
    public static boolean looksBanned(String kickMessage) {
        String text = normalize(kickMessage);
        if (text.isEmpty()) return false;

        for (String phrase : BAN_PHRASES) {
            if (text.contains(phrase)) return true;
        }

        // "banned" on its own is too weak to act on, but next to any ban wording it is a ban
        return text.contains("banned")
                && (text.contains("appeal") || text.contains("unban") || text.contains("punishment")
                || text.contains("hypixel") || text.contains("this server"));
    }

    /** The "Reason: ..." line of a ban, or "" when the message has none. */
    public static String reasonLine(String kickMessage) {
        if (kickMessage == null) return "";
        for (String line : kickMessage.split("\\R")) {
            String plain = stripColours(line).trim();
            int colon = plain.indexOf(':');
            if (colon <= 0) continue;
            String label = plain.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (label.equals("reason") || label.equals("ban reason")) {
                return plain.substring(colon + 1).trim();
            }
        }
        return "";
    }

    /** § colour/format codes, including the §x hex form. */
    private static String stripColours(String text) {
        return text.replaceAll("(?i)\u00a7[0-9a-fk-orx]", " ");
    }
}
