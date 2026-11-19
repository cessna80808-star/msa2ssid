package dev.msa2ssid.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One account. Created by signing the account in with device code, or by pasting a
 * token its owner already has: a refresh token lets the account renew itself, while
 * an existing Minecraft access token ("SSID") is stored as-is and expires with it.
 */
public record Account(
        String name,
        String uuid,
        String xuid,
        String mcAccessToken,
        long mcExpiresAtEpochSec,
        String msRefreshToken,
        String skinUrl,
        long addedAtEpochSec) {

    public boolean hasLiveSession() {
        return mcAccessToken != null && !mcAccessToken.isBlank()
                && mcExpiresAtEpochSec - 120 > Instant.now().getEpochSecond();
    }

    public long sessionSecondsLeft() {
        return mcExpiresAtEpochSec - Instant.now().getEpochSecond();
    }

    public Account withSession(String token, long expiresInSeconds) {
        return new Account(name, uuid, xuid, token,
                Instant.now().getEpochSecond() + Math.max(60, expiresInSeconds),
                msRefreshToken, skinUrl, addedAtEpochSec);
    }

    /**
     * Stores a freshly authenticated session as an account. Used by every sign-in path
     * (device code, pasted token, batch import) so they cannot drift apart.
     */
    public static Account fromSession(Auth.Session session, String msRefreshToken) {
        long now = Instant.now().getEpochSecond();
        return new Account(
                session.name() == null ? "(unnamed)" : session.name(),
                session.uuid(), session.xuid(), session.accessToken(),
                now + Math.max(60, session.expiresInSeconds()),
                msRefreshToken, session.skinUrl(), now);
    }

    /** Updates the profile fields; null arguments keep the existing value (a null uuid
     *  would otherwise turn a rename into a duplicate row). */
    public Account withProfile(String newName, String newUuid, String newXuid, String newSkinUrl) {
        return new Account(
                newName == null ? name : newName,
                newUuid == null ? uuid : newUuid,
                newXuid == null ? xuid : newXuid,
                mcAccessToken, mcExpiresAtEpochSec, msRefreshToken,
                newSkinUrl == null ? skinUrl : newSkinUrl, addedAtEpochSec);
    }

    public Account withRefreshToken(String token) {
        return new Account(name, uuid, xuid, mcAccessToken, mcExpiresAtEpochSec,
                token, skinUrl, addedAtEpochSec);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("uuid", uuid);
        if (xuid != null) m.put("xuid", xuid);
        if (mcAccessToken != null) m.put("mcAccessToken", mcAccessToken);
        m.put("mcExpiresAt", mcExpiresAtEpochSec);
        if (msRefreshToken != null) m.put("msRefreshToken", msRefreshToken);
        if (skinUrl != null) m.put("skinUrl", skinUrl);
        m.put("addedAt", addedAtEpochSec);
        return m;
    }

    public static Account fromMap(Map<String, Object> m) {
        return new Account(
                Json.str(m.get("name")),
                Json.str(m.get("uuid")),
                Json.str(m.get("xuid")),
                Json.str(m.get("mcAccessToken")),
                Json.num(m.get("mcExpiresAt")),
                Json.str(m.get("msRefreshToken")),
                Json.str(m.get("skinUrl")),
                Json.num(m.get("addedAt")));
    }

    /** Formats a 32-char hex id as a dashed UUID; other input passes through. */
    public static String formatUuid(String id) {
        if (id == null) return null;
        String hex = id.replace("-", "");
        if (hex.length() != 32) return id;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }
}
