package dev.msa2ssid.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Microsoft device-code sign-in, the Xbox Live / XSTS / Minecraft services chain,
 * and sign-in from what the account's owner already holds: a Microsoft refresh
 * token, an Xbox/Microsoft access ticket, an existing Minecraft access token (the
 * "SSID"), or a signed-in login.live.com browser cookie.
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

    // ---------------------------------------------------------- browser cookie

    /** The Live-Connect endpoints the launcher's own client uses. */
    public static final String COOKIE_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf";
    private static final String COOKIE_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    private static final String COOKIE_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    /** The authorize endpoint answers a browser, not a library. */
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

    /** Cookies that only exist on a signed-in login.live.com session. */
    private static final String[] SESSION_COOKIES = {
            "MSPAuth", "__Host-MSAAUTHP", "__Host-MSAAUTH", "MSPProf"
    };

    /** True when pasted text is a browser cookie rather than a token. */
    public static boolean looksLikeCookie(String raw) {
        if (raw == null) return false;
        for (String name : SESSION_COOKIES) {
            if (raw.contains(name + "=")) return true;
        }
        // A cookies.txt export separates the name and the value with a tab, so the
        // "name=" form above never matches one: read its columns instead.
        for (String line : raw.split("\\R")) {
            String[] row = netscapeRow(line);
            if (row != null && isSessionCookie(row[1])) return true;
        }
        return false;
    }

    /**
     * One cookies.txt row as {domain, name, value}, whether or not its tabs survived the
     * paste: the mod's input box is a single line, and a collapse to plain spaces still
     * leaves the seven fields a cookies.txt row has.
     *
     * @return the three fields, or null when the line is not a cookies.txt row
     */
    private static String[] netscapeRow(String line) {
        String[] columns = line.split("\t", -1);
        if (columns.length >= 7) {
            return new String[]{columns[0], columns[5].strip(), columns[6].strip()};
        }
        String[] parts = line.strip().split(" {1,}", 7);
        if (parts.length < 7) return null;
        boolean flags = (parts[1].equalsIgnoreCase("TRUE") || parts[1].equalsIgnoreCase("FALSE"))
                && (parts[3].equalsIgnoreCase("TRUE") || parts[3].equalsIgnoreCase("FALSE"))
                && parts[2].equals("/")
                && !parts[4].isEmpty() && parts[4].chars().allMatch(Character::isDigit);
        return flags ? new String[]{parts[0], parts[5], parts[6]} : null;
    }

    /** One of the cookie names only a signed-in login.live.com session has. */
    private static boolean isSessionCookie(String name) {
        for (String cookie : SESSION_COOKIES) {
            if (cookie.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Domains whose cookies a login.live.com authorize request can use. */
    private static boolean isLiveDomain(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        return d.equals("live.com") || d.endsWith(".live.com") || d.equals("login.live.com");
    }

    /**
     * Normalises whatever a browser hands over into a Cookie header: a
     * "Cookie: a=b; c=d" request header, a "a=b; c=d" pair list, or a Netscape
     * cookies.txt export (tab separated, "#HttpOnly_" prefixed). A file export is
     * reduced to its name/value columns, since the rest would go out as garbage.
     */
    public static String cookieHeader(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("paste the cookie first");
        List<String> session = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (String rawLine : raw.split("\\R")) {
            if (rawLine.isBlank()) continue;

            // Split before trimming, so an empty value stays an empty column instead of
            // shifting the name into the value slot.
            String[] row = netscapeRow(rawLine);
            if (row != null) {
                // cookies.txt: domain, flag, path, secure, expiry, name, value
                String domain = row[0].strip();
                if (domain.startsWith("#HttpOnly_")) {
                    domain = domain.substring("#HttpOnly_".length());
                } else if (domain.startsWith("#")) {
                    continue;
                }
                String name = row[1];
                String value = row[2];
                // An export carries every cookie the browser had, including other hosts
                // and placeholder rows; only the live.com ones belong in this request.
                if (name.isEmpty() || value.isEmpty() || !isLiveDomain(domain)) continue;
                (isSessionCookie(name) ? session : other).add(name + "=" + value);
                continue;
            }

            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.regionMatches(true, 0, "cookie:", 0, 7)) line = line.substring(7).strip();
            for (String pair : line.split(";")) {
                String p = pair.strip();
                int eq = p.indexOf('=');
                if (eq <= 0) continue;
                String name = p.substring(0, eq).strip();
                String value = p.substring(eq + 1).strip();
                if (value.isEmpty()) continue;
                (isSessionCookie(name) ? session : other).add(name + "=" + value);
            }
        }
        // The auth cookies go first so they survive a server that trims long headers.
        session.addAll(other);
        String header = String.join("; ", session);
        if (!looksLikeCookie(header)) {
            throw new IllegalArgumentException("that does not look like a sign-in cookie"
                    + " (expected MSPAuth=... or __Host-MSAAUTHP=...)");
        }
        return header;
    }

    /**
     * Signs in with a login.live.com browser cookie the way the launcher does: the
     * cookie answers the OAuth authorize request without a password, and the code
     * it redirects back with is exchanged for a token pair. The refresh token is
     * kept, so the account renews itself like a device-code sign-in. Makes network
     * calls, so call it off the render thread.
     */
    public static TokenLogin loginWithCookie(String rawCookie) throws Exception {
        String header = cookieHeader(rawCookie);
        String verifier = pkceVerifier();
        String url = COOKIE_AUTHORIZE_URL
                + "?client_id=" + enc(DEFAULT_CLIENT_ID)
                + "&redirect_uri=" + enc(COOKIE_REDIRECT_URI)
                + "&response_type=code"
                + "&scope=" + enc(COOKIE_SCOPE)
                + "&code_challenge=" + enc(challenge(verifier))
                + "&code_challenge_method=S256";

        // login.live.com only hands back a code to something that looks like a browser:
        // its own client id is fine, but the header has to carry a browser user agent.
        Http.Redirect redirect = Http.getRedirect(url, Map.of(
                "Cookie", header,
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language", "en-US,en;q=0.9",
                "User-Agent", BROWSER_USER_AGENT));
        // Any redirect can carry the code; only a page means the session was refused.
        int status = redirect.status();
        if (status < 300 || status >= 400) throw new Exception(cookieFailure(status));

        String location = redirect.location();
        String code = queryParam(location, "code");
        if (code == null) {
            String error = queryParam(location, "error");
            if (error != null) throw new Exception("Microsoft refused the sign-in: " + error);
            throw new Exception("the cookie did not return a sign-in code (sent to "
                    + (location == null ? "nowhere" : location) + ") — it has expired, copy a fresh one");
        }

        // The scope has to be repeated here: without it login.live.com answers
        // 500 server_error for a service-scoped code, PKCE or not.
        String form = "client_id=" + enc(DEFAULT_CLIENT_ID)
                + "&code=" + enc(code)
                + "&grant_type=authorization_code"
                + "&redirect_uri=" + enc(COOKIE_REDIRECT_URI)
                + "&scope=" + enc(COOKIE_SCOPE)
                + "&code_verifier=" + enc(verifier);
        MsTokens tokens = parseTokens(Http.send("POST", LIVE_TOKEN_URL, form,
                "application/x-www-form-urlencoded", Map.of("Accept", "application/json")));
        Session session = chain(tokens.accessToken(), "t");
        return new TokenLogin(session, tokens.refreshToken(), "Microsoft account cookie");
    }

    /** Turns a failed authorize step into something actionable. */
    private static String cookieFailure(int status) {
        if (status == 200) {
            return "the cookie is not signed in any more — sign in at login.live.com again"
                    + " (an account that needs a passkey cannot use a cookie)";
        }
        return "sign-in with the cookie failed (HTTP " + status + ")";
    }

    private static String pkceVerifier() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /** One parameter out of a redirect URL's query string. */
    private static String queryParam(String url, String name) {
        if (url == null) return null;
        int question = url.indexOf('?');
        if (question < 0) return null;
        String query = url.substring(question + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || !part.substring(0, eq).equals(name)) continue;
            try {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return part.substring(eq + 1);
            }
        }
        return null;
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
     *
     * A cookies.txt export is kept whole: its tab-separated columns are one sign-in, and
     * cleaning it line by line would collapse the tabs and turn it into seven typos. It is
     * checked before any cleaning, so the raw block reaches cookieHeader() intact.
     */
    public static List<TokenLine> parseTokenList(String text) {
        List<TokenLine> out = new ArrayList<>();
        if (text == null) return out;
        if (looksLikeCookie(text)) {
            out.add(new TokenLine(cookieLabel(text), text));
            return out;
        }
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

    /**
     * A name for a pasted cookie block, so the account is not "unknown" for the seconds
     * before its profile arrives: the JSHP cookie carries the address in its columns.
     */
    private static String cookieLabel(String rawCookies) {
        for (String line : rawCookies.split("\\R")) {
            String[] row = netscapeRow(line);
            if (row == null || !"JSHP".equalsIgnoreCase(row[1])) continue;
            // JSHP is "3$address$first$last$...": take the one field that looks like a mailbox.
            for (String field : row[2].split("\\$")) {
                String address;
                try {
                    address = URLDecoder.decode(field, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    address = field;
                }
                if (address.indexOf('@') > 0) return address;
            }
        }
        return null;
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
        if (looksLikeCookie(rawToken)) return loginWithCookie(rawToken);
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
