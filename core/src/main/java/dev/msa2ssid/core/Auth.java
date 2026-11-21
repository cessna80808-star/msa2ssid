package dev.msa2ssid.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Microsoft device-code sign-in, the Xbox Live / XSTS / Minecraft services chain,
 * and sign-in from a token the account's owner already holds: a Microsoft refresh
 * token, an Xbox/Microsoft access ticket, or an existing Minecraft access token
 * (the "SSID").
 */
public final class Auth {

    public static final String DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    public static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    public static final String SCOPE = "XboxLive.signin offline_access";

    /** The legacy Live token endpoint the "M.C5..." refresh tokens belong to. */
    public static final String LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String LIVE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    /**
     * The Xbox Live first-party app id. It is the client the legacy Live refresh
     * tokens are issued for, so it works without registering anything; a real
     * registered app id can still be supplied on top of it.
     */
    public static final String DEFAULT_CLIENT_ID = "00000000402b5328";

    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private Auth() {
    }

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             int intervalSeconds, long expiresAtEpochSec) {
    }

    public record Session(String name, String uuid, String xuid, String accessToken,
                          long expiresInSeconds, String skinUrl) {
    }

    /**
     * The stored token is dead and nothing can renew it, so the account is beyond
     * recovery without signing in again. Callers use this to drop the account
     * instead of keeping a row that can never work.
     */
    public static final class TokenRejectedException extends Exception {
        public TokenRejectedException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------- device code

    /** Starts a device-code sign-in. {@code userCode} is what the user types at the URL. */
    public static DeviceCode startDeviceCode(String clientId) throws Http.ApiException, Exception {
        String form = "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE);
        String body = Http.send("POST", DEVICE_CODE_URL, form,
                "application/x-www-form-urlencoded", Map.of("Accept", "application/json"));
        Map<String, Object> m = Json.object(body);
        String deviceCode = Json.str(m.get("device_code"));
        String userCode = Json.str(m.get("user_code"));
        String uri = Json.str(m.get("verification_uri"));
        if (deviceCode == null || userCode == null) {
            throw new IllegalStateException("device code response was missing fields: " + body);
        }
        int interval = Math.max(1, (int) Json.num(m.get("interval")));
        long expiresAt = System.currentTimeMillis() / 1000 + Math.max(60, Json.num(m.get("expires_in")));
        return new DeviceCode(deviceCode, userCode, uri == null ? "https://microsoft.com/link" : uri,
                interval, expiresAt);
    }

    /**
     * Polls until the user finishes signing in, the code expires, or the attempt
     * is declined. Blocks the calling thread, so call it off the render thread.
     */
    public static MsTokens awaitDeviceCode(String clientId, DeviceCode dc,
                                           Consumer<String> progress) throws Exception {
        int interval = dc.intervalSeconds();
        String form = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                + "&client_id=" + enc(clientId)
                + "&device_code=" + enc(dc.deviceCode());

        while (System.currentTimeMillis() / 1000 < dc.expiresAtEpochSec()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("sign-in cancelled");
            Thread.sleep(interval * 1000L);
            try {
                String body = Http.send("POST", TOKEN_URL, form,
                        "application/x-www-form-urlencoded", Map.of("Accept", "application/json"));
                return parseTokens(body);
            } catch (Http.ApiException e) {
                String code = Json.str(Json.object(e.body).get("error"));
                if ("authorization_pending".equals(code)) {
                    if (progress != null) progress.accept("waiting for you to sign in...");
                    continue;
                }
                if ("slow_down".equals(code)) {
                    interval += 5;
                    continue;
                }
                if ("expired_token".equals(code)) throw new Exception("the sign-in code expired, try again");
                if ("authorization_declined".equals(code)) throw new Exception("sign-in was declined");
                throw e;
            }
        }
        throw new Exception("the sign-in code expired, try again");
    }

    public record MsTokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    private static MsTokens parseTokens(String body) {
        Map<String, Object> m = Json.object(body);
        String access = Json.str(m.get("access_token"));
        if (access == null) throw new IllegalStateException("token response had no access_token");
        return new MsTokens(access, Json.str(m.get("refresh_token")), Json.num(m.get("expires_in")));
    }

    /** A refreshed Microsoft token plus the RpsTicket prefix it has to be sent with. */
    private record Renewed(MsTokens tokens, String ticketType) {
    }

    /** Refreshes through the legacy Live endpoint, the one "M.C5..." tokens belong to. */
    public static MsTokens refreshLive(String refreshToken, String clientId) throws Exception {
        String form = "client_id=" + enc(clientId)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&scope=" + enc(LIVE_SCOPE);
        String body = Http.send("POST", LIVE_TOKEN_URL, form,
                "application/x-www-form-urlencoded", Map.of("Accept", "application/json"));
        return parseTokens(body);
    }

    /**
     * Redeems a refresh token by whichever path accepts it: the legacy Live endpoint
     * first (the only one that accepts "M.C5..." tokens, and it needs no registration),
     * then a registered app on the v2 consumers endpoint.
     */
    private static Renewed renew(String clientId, String refreshToken) throws Exception {
        boolean hasApp = clientId != null && !clientId.isBlank();
        try {
            return new Renewed(refreshLive(refreshToken, hasApp ? clientId : DEFAULT_CLIENT_ID), "t");
        } catch (Http.ApiException e) {
            if (!hasApp || Throttle.rateLimited(e)) throw e;
            return new Renewed(refreshMicrosoft(clientId, refreshToken), "d");
        }
    }

    /** Exchanges a refresh token for a fresh Microsoft access token. */
    public static MsTokens refreshMicrosoft(String clientId, String refreshToken) throws Exception {
        String form = "grant_type=refresh_token"
                + "&client_id=" + enc(clientId)
                + "&refresh_token=" + enc(refreshToken)
                + "&scope=" + enc(SCOPE);
        String body = Http.send("POST", TOKEN_URL, form,
                "application/x-www-form-urlencoded", Map.of("Accept", "application/json"));
        return parseTokens(body);
    }

    // ----------------------------------------------------------- pasted tokens

    /** How a pasted token was understood, plus the session it produced. */
    public record TokenLogin(Session session, String msRefreshToken, String kind) {
    }

    /** A pasted token carries no expiry, so an SSID is assumed to last one day. */
    private static final long ASSUMED_SESSION_SECONDS = 86400;

    /** Shorter than this and a line is a typo, not a token. */
    private static final int MIN_TOKEN_LENGTH = 20;

    /**
     * Strips whitespace, wrapping quotes and a literal "d="/"t=" prefix, so a token
     * copied out of a launcher file, a chat message or a login URL still works.
     */
    public static String cleanToken(String raw) {
        if (raw == null) return "";
        String t = raw.replaceAll("\\s+", "");
        boolean quoted = t.length() > 1
                && ((t.charAt(0) == '"' && t.endsWith("\""))
                || (t.charAt(0) == '\'' && t.endsWith("'")));
        if (quoted) t = t.substring(1, t.length() - 1);
        if (t.startsWith("d=") || t.startsWith("t=")) t = t.substring(2);
        return t;
    }

    /** One account line from a pasted list: an optional label plus the token. */
    public record TokenLine(String label, String token) {
    }

    /**
     * Splits pasted text into token lines: "label:token" or a bare token, one per line.
     * Blank lines and "#" comments are dropped, so an exported account set works as-is.
     */
    public static List<TokenLine> parseTokenList(String text) {
        List<TokenLine> out = new ArrayList<>();
        if (text == null) return out;
        for (String rawLine : text.split("\\R")) {
            String line = cleanToken(rawLine);
            if (line.isEmpty() || line.startsWith("#")) continue;

            int colon = line.indexOf(':');
            boolean labelled = colon > 0 && colon <= 40 && line.length() - colon > MIN_TOKEN_LENGTH;
            String token = labelled ? line.substring(colon + 1) : line;
            if (token.length() < MIN_TOKEN_LENGTH) continue;
            out.add(new TokenLine(labelled ? line.substring(0, colon) : null, token));
        }
        return out;
    }

    /** True for Microsoft OAuth refresh tokens and Xbox compact tickets ("M.C..."). */
    public static boolean looksLikeRefreshToken(String token) {
        if (token == null) return false;
        return token.contains(".T.") || token.startsWith("0.A")
                || token.startsWith("1.A") || token.startsWith("M.C");
    }

    /** True when the string is shaped like a Minecraft access token (an SSID). */
    public static boolean looksLikeJwt(String token) {
        return token != null && token.startsWith("eyJ") && token.indexOf('.') > 0;
    }

    /**
     * Turns a pasted token into a working session, accepting any of the three shapes
     * an owner is likely to have. A refresh token is kept so the account can renew
     * itself later; an SSID is stored as-is and simply expires with Minecraft's own
     * session. Makes network calls, so call it off the render thread.
     */
    public static TokenLogin loginWithToken(String clientId, String rawToken) throws Exception {
        String token = cleanToken(rawToken);
        if (token.isEmpty()) throw new IllegalArgumentException("paste a token first");
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("that is too short to be a token");
        }

        Exception refreshFailure = null;
        if (looksLikeRefreshToken(token)) {
            try {
                Renewed renewed = renew(clientId, token);
                Session session = chain(renewed.tokens().accessToken(), renewed.ticketType());
                String kept = renewed.tokens().refreshToken() == null
                        ? token : renewed.tokens().refreshToken();
                return new TokenLogin(session, kept, "Microsoft refresh token");
            } catch (Exception e) {
                if (Throttle.rateLimited(e)) throw e;
                refreshFailure = e;
            }
        }

        // An existing Minecraft access token already works on its own.
        try {
            return new TokenLogin(profile(token, ASSUMED_SESSION_SECONDS), null,
                    "Minecraft access token (SSID)");
        } catch (Http.ApiException e) {
            // A 429 must reach the import's retry, not be mistaken for a bad token.
            if (e.status >= 500 || Throttle.rateLimited(e)) throw e;
        }

        String preferred = token.startsWith("M.") ? "t" : "d";
        Exception firstFailure = null;
        for (String ticketType : new String[]{preferred, preferred.equals("t") ? "d" : "t"}) {
            try {
                return new TokenLogin(chain(token, ticketType), null, "Microsoft access ticket");
            } catch (Exception e) {
                if (Throttle.rateLimited(e)) throw e;
                if (firstFailure == null) firstFailure = e;
            }
        }
        Exception reason = refreshFailure == null ? firstFailure : refreshFailure;
        throw new Exception("token rejected — "
                + (reason == null ? "no usable token" : describeShort(reason)));
    }

    private static String describeShort(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    // -------------------------------------------------------------------- chain

    /** Runs Xbox Live -> XSTS -> Minecraft login -> profile. */
    public static Session chain(String msAccessToken) throws Exception {
        return chain(msAccessToken, "d");
    }

    /**
     * The same chain with an explicit RpsTicket prefix: "d" for a Microsoft access
     * token, "t" for a compact Xbox ticket (the "M." form launchers store).
     */
    public static Session chain(String msAccessToken, String ticketType) throws Exception {
        String xbl = xbl(msAccessToken, ticketType);
        Map<String, Object> xblRoot = Json.object(xbl);
        String xblToken = Json.str(xblRoot.get("Token"));
        Map<String, Object> xui = firstXui(xblRoot);
        String uhs = Json.str(xui.get("uhs"));
        if (xblToken == null || uhs == null) throw new IllegalStateException("xbox auth response was incomplete");

        String xstsToken = xsts(xblToken);

        Map<String, Object> loginBody = new LinkedHashMap<>();
        loginBody.put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        String login = Http.send("POST", MC_LOGIN_URL, Json.write(loginBody), "application/json",
                Map.of("Accept", "application/json"));
        Map<String, Object> lm = Json.object(login);
        String mcToken = Json.str(lm.get("access_token"));
        if (mcToken == null) throw new IllegalStateException("minecraft login returned no access_token");
        long expiresIn = Json.num(lm.get("expires_in"));

        Session profile = profile(mcToken, expiresIn);
        return new Session(profile.name(), profile.uuid(), Json.str(xui.get("xid")),
                mcToken, expiresIn, profile.skinUrl());
    }

    private static String xbl(String msAccessToken, String ticketType) throws Exception {
        Map<String, Object> props = new LinkedHashMap<>();
        // AuthMethod/SiteName are not optional: without them Xbox Live answers a bare
        // HTTP 400 with an empty body, which looks exactly like a bad token.
        props.put("AuthMethod", "RPS");
        props.put("SiteName", "user.auth.xboxlive.com");
        props.put("RpsTicket", ticketType + "=" + msAccessToken);
        props.put("SandboxId", "RETAIL");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Properties", props);
        body.put("RelyingParty", "http://auth.xboxlive.com");
        body.put("TokenType", "JWT");
        return Http.send("POST", XBL_URL, Json.write(body), "application/json",
                Map.of("Accept", "application/json", "x-xbl-contract-version", "1"));
    }

    private static String xsts(String xblToken) throws Exception {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("SandboxId", "RETAIL");
        props.put("UserTokens", List.of(xblToken));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Properties", props);
        body.put("RelyingParty", "rp://api.minecraftservices.com/");
        body.put("TokenType", "JWT");
        try {
            Map<String, Object> m = Json.object(Http.send("POST", XSTS_URL, Json.write(body),
                    "application/json",
                    Map.of("Accept", "application/json", "x-xbl-contract-version", "1")));
            String token = Json.str(m.get("Token"));
            if (token == null) throw new IllegalStateException("xsts returned no Token");
            return token;
        } catch (Http.ApiException e) {
            String hint = xerrHint(e.body);
            throw new Http.ApiException(hint == null ? e.getMessage() : e.getMessage() + " — " + hint,
                    e.status, e.body);
        }
    }

    /** Reads the profile (name, uuid, skin) for a Minecraft access token. */
    public static Session profile(String mcAccessToken, long expiresInSeconds) throws Exception {
        Map<String, Object> m = Json.object(Http.send("GET", MC_PROFILE_URL, null, null,
                Map.of("Authorization", "Bearer " + mcAccessToken, "Accept", "application/json")));
        String skin = null;
        for (Object o : Json.asArray(m.get("skins"))) {
            Map<String, Object> s = Json.asObject(o);
            if ("ACTIVE".equals(Json.str(s.get("state"))) || skin == null) {
                String url = Json.str(s.get("url"));
                if (url != null) skin = url;
            }
        }
        return new Session(Json.str(m.get("name")), Account.formatUuid(Json.str(m.get("id"))),
                null, mcAccessToken, expiresInSeconds, skin);
    }

    /**
     * Returns a live session for the account, refreshing the Microsoft token and
     * re-running the chain when the stored Minecraft token is close to expiry.
     */
    public static Session ensureSession(String clientId, Account account) throws Exception {
        if (account.hasLiveSession()) {
            try {
                return profile(account.mcAccessToken(), Math.max(1, account.sessionSecondsLeft()));
            } catch (Http.ApiException e) {
                if (e.status != 401 && e.status != 403) throw e;
                if (account.msRefreshToken() == null) {
                    throw new TokenRejectedException(
                            "its session expired and a pasted token cannot be renewed");
                }
            }
        }
        if (account.msRefreshToken() == null) {
            throw new TokenRejectedException("its session expired and there is no refresh token");
        }
        Renewed renewed;
        try {
            renewed = renew(clientId, account.msRefreshToken());
        } catch (Http.ApiException e) {
            if (e.status == 400 || e.status == 401 || e.status == 403) {
                throw new TokenRejectedException("its refresh token was rejected (expired or revoked)");
            }
            throw e;
        }
        try {
            return chain(renewed.tokens().accessToken(), renewed.ticketType());
        } catch (Http.ApiException e) {
            if (e.status == 401 || e.status == 403) {
                throw new TokenRejectedException("the services rejected this account's token");
            }
            throw e;
        }
    }

    // --------------------------------------------------------------------- skins

    public static final String SKIN_URL = "https://api.minecraftservices.com/minecraft/profile/skins";

    /**
     * Uploads a skin to the account the token belongs to. This is the real,
     * server-side skin: other players see it. Uses multipart/form-data because
     * Mojang's firewall rejects the JSON/url form.
     */
    public static Session uploadSkin(String mcAccessToken, byte[] png, String variant) throws Exception {
        String model = "slim".equalsIgnoreCase(variant) ? "slim" : "classic";
        Http.Multipart body = Http.multipart(Map.of("variant", model), "file", "skin.png", "image/png", png);
        Http.sendBytes("POST", SKIN_URL, body.body(), body.contentType(),
                Map.of("Authorization", "Bearer " + mcAccessToken, "Accept", "application/json"));
        return profile(mcAccessToken, 0);
    }

    /** Downloads a skin URL and uploads the bytes, since the url form is blocked. */
    public static Session uploadSkinFromUrl(String mcAccessToken, String url, String variant) throws Exception {
        byte[] png = Http.getBytes(url);
        Skins.validateSkin(png);
        return uploadSkin(mcAccessToken, png, variant);
    }

    /** Resets the account to the default skin. */
    public static Session resetSkin(String mcAccessToken) throws Exception {
        Http.send("DELETE", SKIN_URL + "/active", null, null,
                Map.of("Authorization", "Bearer " + mcAccessToken, "Accept", "application/json"));
        return profile(mcAccessToken, 0);
    }

    // ----------------------------------------------------------------- renaming

    public static final String RENAME_URL = "https://api.minecraftservices.com/minecraft/profile/name/";

    public record NameCheck(boolean available, String status) {
    }

    /** Asks Microsoft whether a name can be taken. */
    public static NameCheck nameAvailable(String mcAccessToken, String name) throws Exception {
        Map<String, Object> m = Json.object(Http.send("GET",
                RENAME_URL + enc(name) + "/available", null, null,
                Map.of("Authorization", "Bearer " + mcAccessToken, "Accept", "application/json")));
        String status = Json.str(m.get("status"));
        return new NameCheck("AVAILABLE".equals(status), status == null ? "UNKNOWN" : status);
    }

    /**
     * Renames the account the token belongs to. This is the official endpoint and
     * is subject to Microsoft's 30-day cooldown; it changes the real account name.
     */
    public static Session rename(String mcAccessToken, String requestedName) throws Exception {
        String body;
        try {
            body = Http.send("POST", RENAME_URL + enc(requestedName), "{}", "application/json",
                    Map.of("Authorization", "Bearer " + mcAccessToken, "Accept", "application/json"));
        } catch (Http.ApiException e) {
            if (e.status != 405 && e.status != 404) throw e;
            body = Http.send("PUT", RENAME_URL + enc(requestedName), "{}", "application/json",
                    Map.of("Authorization", "Bearer " + mcAccessToken, "Accept", "application/json"));
        }
        Map<String, Object> m = Json.object(body);
        String name = Json.str(m.get("name"));
        return new Session(name == null ? requestedName : name,
                Account.formatUuid(Json.str(m.get("id"))), null, mcAccessToken, 0, null);
    }

    // -------------------------------------------------------------------- utils

    private static Map<String, Object> firstXui(Map<String, Object> tokenResponse) {
        Map<String, Object> claims = Json.asObject(tokenResponse.get("DisplayClaims"));
        List<Object> xui = Json.asArray(claims.get("xui"));
        return xui.isEmpty() ? Map.of() : Json.asObject(xui.get(0));
    }

    static String xerrHint(String body) {
        int idx = body == null ? -1 : body.indexOf("\"XErr\"");
        if (idx < 0) return null;
        int start = body.indexOf(':', idx);
        if (start < 0) return null;
        int end = start + 1;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || Character.isWhitespace(body.charAt(end)))) end++;
        String code;
        try {
            code = Long.toString(Long.parseLong(body.substring(start + 1, end).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (code) {
            case "2148916233" -> "this account has no Xbox profile; sign in once at xbox.com";
            case "2148916235" -> "Xbox Live is not available in this account's country";
            case "2148916236", "2148916237" -> "the account needs adult verification";
            case "2148916238" -> "child account: add it to a Microsoft family first";
            case "2148916227" -> "this account is banned from Xbox Live";
            default -> null;
        };
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
