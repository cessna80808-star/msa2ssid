package dev.msa2ssid;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * msa2ssid — takes a Microsoft account token (compact "M." ticket or JWT) or an
 * existing Minecraft access token, runs the Xbox Live / XSTS / Minecraft services
 * chain and prints the resulting Minecraft session plus the account's profile.
 *
 * Only use this with accounts you own.
 */
public final class Main {

    // ------------------------------------------------------------------ config

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration REQ_TIMEOUT = Duration.ofSeconds(45);
    private static final String USER_AGENT = "msa2ssid/1.0";

    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String SESSION_JOIN_URL = "https://sessionserver.mojang.com/session/minecraft/join";
    private static final String LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf";

    private static final String DEFAULT_CLIENT_ID = "00000000402b5328";

    private static boolean verbose = false;

    // ------------------------------------------------------------------ models

    record Task(String label, String token, String kind) {

        static Task of(String raw, String kind, String label) {
            String value = raw == null ? "" : raw.trim();
            String name = label;
            // Accept "Label:token" (the usual shape of a pasted account line).
            int colon = value.indexOf(':');
            if (name == null && colon > 0 && colon <= 40) {
                String head = value.substring(0, colon).trim();
                String tail = value.substring(colon + 1).trim();
                if (!head.isEmpty() && !head.contains(" ") && tail.length() > 40) {
                    name = head;
                    value = tail;
                }
            }
            return new Task(name, value, kind);
        }
    }

    record Login(String accessToken, long expiresIn) {
    }

    record Credentials(String label, String name, String uuid, String xuid,
                       String accessToken, long expiresIn, String source, Map<String, Object> profile) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (label != null) m.put("label", label);
            m.put("name", name);
            m.put("uuid", uuid);
            if (xuid != null) m.put("xuid", xuid);
            m.put("accessToken", accessToken);
            m.put("source", source);
            if (expiresIn > 0) {
                m.put("expiresIn", expiresIn);
                m.put("expiresAt", Instant.now().plusSeconds(expiresIn).toString());
            }
            if (profile != null) m.put("profile", profile);
            return m;
        }
    }

    static final class ApiError extends Exception {
        final int status;
        final String body;

        ApiError(String message, int status, String body) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }

    // -------------------------------------------------------------------- main

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (ApiError e) {
            System.err.println("error: " + e.getMessage());
            code = 1;
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("try: java -jar msa2ssid.jar --help");
            code = 2;
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            code = 1;
        }
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] args) throws Exception {
        List<Task> tasks = new ArrayList<>();
        String forcedTicket = null;
        String clientId = DEFAULT_CLIENT_ID;
        String joinHash = null;
        boolean json = false;
        String pendingLabel = null;
        String file = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help", "help" -> {
                    usage();
                    return 0;
                }
                case "-V", "--version" -> {
                    System.out.println("msa2ssid 1.0");
                    return 0;
                }
                case "-v", "--verbose" -> verbose = true;
                case "-j", "--json" -> json = true;
                case "--ticket-type" -> forcedTicket = ticketType(arg(args, ++i, a));
                case "--client-id" -> clientId = arg(args, ++i, a);
                case "--label" -> pendingLabel = arg(args, ++i, a);
                case "--join-server", "--join-server-id", "--join" -> joinHash = arg(args, ++i, a);
                case "-f", "--file" -> file = arg(args, ++i, a);
                case "--msa", "--microsoft" -> {
                    tasks.add(Task.of(arg(args, ++i, a), "msa", pendingLabel));
                    pendingLabel = null;
                }
                case "--ssid", "--session" -> {
                    tasks.add(Task.of(arg(args, ++i, a), "ssid", pendingLabel));
                    pendingLabel = null;
                }
                case "--refresh" -> {
                    tasks.add(Task.of(arg(args, ++i, a), "refresh", pendingLabel));
                    pendingLabel = null;
                }
                case "-t", "--token" -> {
                    tasks.add(Task.of(arg(args, ++i, a), "auto", pendingLabel));
                    pendingLabel = null;
                }
                default -> {
                    if (a.startsWith("-") && a.length() > 1) {
                        throw new IllegalArgumentException("unknown option: " + a);
                    }
                    tasks.add(Task.of(a, "auto", pendingLabel));
                    pendingLabel = null;
                }
            }
        }

        if (file != null) {
            List<String> lines;
            if (file.equals("-")) {
                lines = new ArrayList<>();
                try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    for (String line; (line = in.readLine()) != null; ) lines.add(line);
                }
            } else {
                lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
            }
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                tasks.add(Task.of(trimmed, "auto", null));
            }
        }

        if (tasks.isEmpty()) {
            usage();
            return 2;
        }

        int failures = 0;
        for (int n = 0; n < tasks.size(); n++) {
            Task t = tasks.get(n);
            if (json) {
                try {
                    Credentials c = authenticate(t, clientId, forcedTicket, joinHash);
                    System.out.println(Json.write(c.toMap()));
                } catch (ApiError e) {
                    failures++;
                    Map<String, Object> err = new LinkedHashMap<>();
                    if (t.label() != null) err.put("label", t.label());
                    err.put("error", e.getMessage());
                    err.put("status", e.status);
                    System.out.println(Json.write(err));
                }
                continue;
            }

            if (tasks.size() > 1 && n > 0) System.out.println();
            try {
                Credentials c = authenticate(t, clientId, forcedTicket, joinHash);
                printHuman(c);
            } catch (ApiError e) {
                failures++;
                System.err.println((t.label() == null ? "" : t.label() + ": ")
                        + "error: " + e.getMessage());
            }
        }
        return failures == 0 ? 0 : 1;
    }

    private static void usage() {
        System.out.print("""
                msa2ssid 1.0 — turn a Microsoft account token or an existing Minecraft session
                token into a working Minecraft session (profile + access token).

                USAGE
                  java -jar msa2ssid.jar <label:token> [options]
                  java -jar msa2ssid.jar --msa <token> [options]
                  java -jar msa2ssid.jar --file accounts.txt [options]

                INPUT  (repeatable; a bare argument is auto-detected)
                  --msa <token>        Microsoft/Xbox token ("M." compact ticket or a JWT)
                  --ssid <token>       an existing Minecraft access token
                  --refresh <token>    Microsoft OAuth refresh token
                  -t, --token <token>  same as --msa, but auto-detects the kind
                  -f, --file <path>    one "label:token" per line, "-" for stdin

                OPTIONS
                  --label <name>       label for the next token on the command line
                  --ticket-type t|d    force the Xbox RpsTicket prefix (default: auto)
                  --client-id <id>     OAuth client id used with --refresh
                                       (default %s)
                  --join-server <hash> also POST session/minecraft/join with this
                                       server hash, as a client would on connect
                  -j, --json           machine-readable output, one object per line
                  -v, --verbose        log every HTTP request
                  -h, --help           this text

                OUTPUT FIELDS
                  name, uuid, xuid, plus the Minecraft access token (the "SSID" a
                  client uses to authenticate to online-mode servers) and its lifetime.

                EXAMPLES
                  java -jar msa2ssid.jar 'example_user:M.C000_EXAMPLE.0.U.Example...'
                  java -jar msa2ssid.jar --msa 'M.C000_EXAMPLE...' --json
                  java -jar msa2ssid.jar --ssid 'eyJhbGciOi...' --join-server 0123abcd...
                  java -jar msa2ssid.jar --refresh 'M.C000_EXAMPLE...T...' --client-id 0000

                Only use tokens for accounts you own.
                """.formatted(DEFAULT_CLIENT_ID));
    }

    // ------------------------------------------------------------- orchestration

    static Credentials authenticate(Task t, String clientId, String forcedTicket, String joinHash)
            throws Exception {
        if (t.token().isEmpty()) throw new IllegalArgumentException("empty token for " + (t.label() == null ? "input" : t.label()));

        String kind = t.kind() == null ? "auto" : t.kind();
        switch (kind) {
            case "ssid":
                return fromSession(t, joinHash);
            case "refresh": {
                String ms = refreshMicrosoftToken(t.token(), clientId);
                return chain(t, ms, "t", "microsoft refresh token", forcedTicket, joinHash);
            }
            case "msa":
                return chain(t, t.token(), guessTicketType(t.token()), "microsoft token", forcedTicket, joinHash);
            default:
                break;
        }

        if (t.token().contains(".T.")) {
            String ms = refreshMicrosoftToken(t.token(), clientId);
            return chain(t, ms, "t", "microsoft refresh token", forcedTicket, joinHash);
        }
        if (looksLikeMicrosoftToken(t.token())) {
            return chain(t, t.token(), guessTicketType(t.token()), "microsoft token", forcedTicket, joinHash);
        }
        try {
            return fromSession(t, joinHash);
        } catch (ApiError e) {
            if (e.status != 401 && e.status != 403) throw e;
            if (verbose) System.err.println("  (not a Minecraft session token, trying the Microsoft chain)");
        }
        return chain(t, t.token(), guessTicketType(t.token()), "microsoft token", forcedTicket, joinHash);
    }

    static boolean looksLikeMicrosoftToken(String token) {
        if (token.startsWith("M.")) return true;                 // Xbox compact ticket
        if (token.startsWith("Ew") || token.startsWith("ey")) return true; // JWT
        if (token.startsWith("0.A") || token.startsWith("1.A")) return true;
        return false;
    }

    static String guessTicketType(String token) {
        return token.startsWith("M.") ? "t" : "d";
    }

    static String ticketType(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (!s.equals("t") && !s.equals("d")) throw new IllegalArgumentException("--ticket-type must be t or d");
        return s;
    }

    // ------------------------------------------------------------- auth: session

    static Credentials fromSession(Task t, String joinHash) throws Exception {
        Map<String, Object> profile = fetchProfile(t.token());
        Credentials c = fromProfile(t.label(), "minecraft session token", t.token(), -1, profile, null);
        if (joinHash != null) joinServer(c, joinHash);
        return c;
    }

    // ------------------------------------------------------------ auth: chain

    static Credentials chain(Task t, String msToken, String hint, String source,
                             String forcedTicket, String joinHash) throws Exception {
        String[] types = forcedTicket != null
                ? new String[]{forcedTicket}
                : (hint.equals("t") ? new String[]{"t", "d"} : new String[]{"d", "t"});

        Map<String, Object> xbl = null;
        ApiError xblError = null;
        String usedType = hint;
        for (String type : types) {
            try {
                xbl = xblAuthenticate(msToken, type);
                usedType = type;
                break;
            } catch (ApiError e) {
                xblError = e;
                if (verbose) System.err.println("  (xbox auth with ticket type " + type + " failed)");
            }
        }
        if (xbl == null) throw xblError;

        String xblToken = Json.str(xbl.get("Token"));
        Map<String, Object> xui = firstXui(xbl);
        String uhs = Json.str(xui.get("uhs"));

        String xstsToken = xstsAuthorize(xblToken);
        Login login = loginWithXbox(uhs, xstsToken);
        Map<String, Object> profile = fetchProfile(login.accessToken());

        String xuid = Json.str(xui.get("xid"));
        Credentials c = fromProfile(t.label(), source + " (RpsTicket " + usedType + "=)",
                login.accessToken(), login.expiresIn(), profile, xuid);
        if (joinHash != null) joinServer(c, joinHash);
        return c;
    }

    static Credentials fromProfile(String label, String source, String accessToken, long expiresIn,
                                   Map<String, Object> profile, String xuid) {
        String id = Json.str(profile.get("id"));
        return new Credentials(label, Json.str(profile.get("name")), formatUuid(id),
                xuid, accessToken, expiresIn, source, profile);
    }

    static Map<String, Object> xblAuthenticate(String msToken, String ticketType) throws Exception {
        Map<String, Object> props = new LinkedHashMap<>();
        // Without these two, Xbox Live answers 400 with an empty body.
        props.put("AuthMethod", "RPS");
        props.put("SiteName", "user.auth.xboxlive.com");
        props.put("RpsTicket", ticketType + "=" + msToken);
        props.put("SandboxId", "RETAIL");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Properties", props);
        body.put("RelyingParty", "http://auth.xboxlive.com");
        body.put("TokenType", "JWT");

        HttpResponse<String> r = send("POST", XBL_URL, Json.write(body), "application/json",
                Map.of("Accept", "application/json", "x-xbl-contract-version", "1"));
        if (r.statusCode() / 100 != 2) {
            String msg = describe("xbox live user auth", r.statusCode(), r.body());
            if (r.body() == null || r.body().isBlank()) {
                msg += " (the token was rejected: expired, invalid, or not an Xbox ticket)";
            }
            throw new ApiError(msg, r.statusCode(), r.body());
        }
        return Json.asObject(Json.parse(r.body()));
    }

    static String xstsAuthorize(String xblToken) throws Exception {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("SandboxId", "RETAIL");
        props.put("UserTokens", List.of(xblToken));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Properties", props);
        body.put("RelyingParty", "rp://api.minecraftservices.com/");
        body.put("TokenType", "JWT");

        HttpResponse<String> r = send("POST", XSTS_URL, Json.write(body), "application/json",
                Map.of("Accept", "application/json", "x-xbl-contract-version", "1"));
        if (r.statusCode() / 100 != 2) {
            String msg = describe("xbox live xsts", r.statusCode(), r.body());
            String hint = xerrHint(r.body());
            throw new ApiError(hint == null ? msg : msg + " — " + hint, r.statusCode(), r.body());
        }
        String token = Json.str(Json.asObject(Json.parse(r.body())).get("Token"));
        if (token == null) throw new ApiError("xsts returned no Token", r.statusCode(), r.body());
        return token;
    }

    static Login loginWithXbox(String uhs, String xstsToken) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);

        HttpResponse<String> r = send("POST", MC_LOGIN_URL, Json.write(body), "application/json",
                Map.of("Accept", "application/json"));
        if (r.statusCode() / 100 != 2) {
            throw new ApiError(describe("minecraft login", r.statusCode(), r.body()),
                    r.statusCode(), r.body());
        }
        Map<String, Object> m = Json.asObject(Json.parse(r.body()));
        String token = Json.str(m.get("access_token"));
        if (token == null) throw new ApiError("minecraft login returned no access_token", r.statusCode(), r.body());
        return new Login(token, Json.num(m.get("expires_in")));
    }

    static Map<String, Object> fetchProfile(String accessToken) throws Exception {
        HttpResponse<String> r = send("GET", MC_PROFILE_URL, null, null,
                Map.of("Authorization", "Bearer " + accessToken, "Accept", "application/json"));
        if (r.statusCode() / 100 != 2) {
            String msg = describe("minecraft profile", r.statusCode(), r.body());
            if (r.statusCode() == 404) {
                msg += " — this account does not own Minecraft: Java Edition";
            } else if (r.statusCode() == 401 || r.statusCode() == 403) {
                msg += " — not a valid or active Minecraft session token";
            }
            throw new ApiError(msg, r.statusCode(), r.body());
        }
        return Json.asObject(Json.parse(r.body()));
    }

    static void joinServer(Credentials c, String serverHash) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", c.accessToken());
        body.put("selectedProfile", c.uuid() == null ? "" : c.uuid().replace("-", ""));
        body.put("serverId", serverHash);

        HttpResponse<String> r = send("POST", SESSION_JOIN_URL, Json.write(body), "application/json",
                Map.of("Accept", "application/json"));
        if (r.statusCode() / 100 != 2) {
            throw new ApiError(describe("session join", r.statusCode(), r.body()), r.statusCode(), r.body());
        }
        System.out.println("  join           ok (server accepted the session hash)");
    }

    static String refreshMicrosoftToken(String refreshToken, String clientId) throws Exception {
        String form = "client_id=" + enc(clientId)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(refreshToken)
                + "&scope=" + enc("service::user.auth.xboxlive.com::MBI_SSL");
        HttpResponse<String> r = send("POST", LIVE_TOKEN_URL, form,
                "application/x-www-form-urlencoded", Map.of("Accept", "application/json"));
        if (r.statusCode() / 100 != 2) {
            throw new ApiError(describe("microsoft token refresh", r.statusCode(), r.body()),
                    r.statusCode(), r.body());
        }
        Map<String, Object> m = Json.asObject(Json.parse(r.body()));
        String accessToken = Json.str(m.get("access_token"));
        if (accessToken == null) throw new ApiError("microsoft token refresh returned no access_token",
                r.statusCode(), r.body());
        return accessToken;
    }

    // ------------------------------------------------------------------- output

    static void printHuman(Credentials c) {
        if (c.label() != null) System.out.println(c.label());
        System.out.printf("  %-13s %s%n", "name", orDash(c.name()));
        System.out.printf("  %-13s %s%n", "uuid", orDash(c.uuid()));
        if (c.xuid() != null) System.out.printf("  %-13s %s%n", "xuid", c.xuid());
        System.out.printf("  %-13s %s%n", "source", c.source());
        System.out.printf("  %-13s %s%n", "expires in", humanDuration(c.expiresIn()));
        System.out.printf("  %-13s %s%n", "access token", c.accessToken());
    }

    static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    static String humanDuration(long seconds) {
        if (seconds <= 0) return "unknown (already-issued session token)";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return seconds + "s (" + h + "h " + m + "m)";
    }

    static String formatUuid(String id) {
        if (id == null) return null;
        String hex = id.replace("-", "");
        if (hex.length() != 32) return id;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    // ---------------------------------------------------------------------- http

    static HttpResponse<String> send(String method, String url, String body, String contentType,
                                     Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQ_TIMEOUT)
                .header("User-Agent", USER_AGENT);
        if (headers != null) headers.forEach(b::header);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", contentType == null ? "application/json" : contentType);
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        if (verbose) System.err.println("-> " + method + " " + url);
        try {
            HttpResponse<String> r = HTTP.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (verbose) System.err.println("<- " + r.statusCode() + " " + url);
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while calling " + url, e);
        }
    }

    static String describe(String endpoint, int status, String body) {
        String detail = null;
        try {
            Object parsed = Json.parse(body == null ? "" : body);
            if (parsed instanceof Map<?, ?> raw) {
                Map<String, Object> m = Json.asObject(raw);
                for (String key : new String[]{"errorMessage", "error_description", "error", "Message", "message"}) {
                    Object v = m.get(key);
                    if (v instanceof String s && !s.isBlank()) {
                        detail = s;
                        break;
                    }
                }
                Object xerr = m.get("XErr");
                if (xerr != null) detail = (detail == null || detail.isBlank() ? "" : detail + " ") + "XErr=" + xerr;
            }
        } catch (RuntimeException ignored) {
            // not JSON: fall back to the raw body
        }
        if (detail == null) detail = body == null ? "" : body.strip();
        if (detail.length() > 300) detail = detail.substring(0, 300) + "...";
        return endpoint + " returned HTTP " + status + (detail.isEmpty() ? "" : ": " + detail);
    }

    static String xerrHint(String body) {
        int idx = body == null ? -1 : body.indexOf("\"XErr\"");
        if (idx < 0) return null;
        int start = body.indexOf(':', idx);
        if (start < 0) return null;
        int end = start + 1;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || Character.isWhitespace(body.charAt(end)))) end++;
        String digits = body.substring(start + 1, end).trim();
        String code;
        try {
            code = Long.toString(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (code) {
            case "2148916233" -> "no Xbox profile on this account; sign in once at https://www.xbox.com to create one";
            case "2148916235" -> "Xbox Live is not available in this account's country";
            case "2148916236", "2148916237" -> "the account needs adult verification";
            case "2148916238" -> "child account: it must be added to a Microsoft family first";
            case "2148916227" -> "this account has been banned from Xbox Live";
            default -> null;
        };
    }

    static Map<String, Object> firstXui(Map<String, Object> tokenResponse) {
        Map<String, Object> claims = Json.asObject(tokenResponse.get("DisplayClaims"));
        List<Object> xui = Json.asArray(claims.get("xui"));
        return xui.isEmpty() ? Map.of() : Json.asObject(xui.get(0));
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String arg(String[] args, int i, String flag) {
        if (i >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        return args[i];
    }

    // ---------------------------------------------------------------------- json

    static final class Json {

        static Object parse(String s) {
            Parser p = new Parser(s);
            Object v = p.value();
            p.ws();
            if (p.i < p.s.length()) throw p.err("trailing data");
            return v;
        }

        static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }

        static long num(Object o) {
            if (o instanceof Number n) return n.longValue();
            if (o instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> asObject(Object o) {
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        static List<Object> asArray(Object o) {
            return o instanceof List<?> l ? (List<Object>) l : List.of();
        }

        static String write(Object v) {
            StringBuilder b = new StringBuilder();
            writeTo(v, b);
            return b.toString();
        }

        static void writeTo(Object v, StringBuilder b) {
            if (v == null) {
                b.append("null");
            } else if (v instanceof String s) {
                quote(s, b);
            } else if (v instanceof Boolean || v instanceof Number) {
                b.append(v);
            } else if (v instanceof Map<?, ?> m) {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), b);
                    b.append(':');
                    writeTo(e.getValue(), b);
                }
                b.append('}');
            } else if (v instanceof Iterable<?> it) {
                b.append('[');
                boolean first = true;
                for (Object o : it) {
                    if (!first) b.append(',');
                    first = false;
                    writeTo(o, b);
                }
                b.append(']');
            } else {
                quote(String.valueOf(v), b);
            }
        }

        static void quote(String s, StringBuilder b) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    case '\b' -> b.append("\\b");
                    case '\f' -> b.append("\\f");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        }

        private static final class Parser {
            final String s;
            int i;

            Parser(String s) {
                this.s = s;
            }

            RuntimeException err(String msg) {
                return new IllegalArgumentException(msg + " at offset " + i);
            }

            void ws() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }

            Object value() {
                ws();
                if (i >= s.length()) throw err("unexpected end of input");
                char c = s.charAt(i);
                if (c == '{') return object();
                if (c == '[') return array();
                if (c == '"') return string();
                if (c == 't') {
                    literal("true");
                    return Boolean.TRUE;
                }
                if (c == 'f') {
                    literal("false");
                    return Boolean.FALSE;
                }
                if (c == 'n') {
                    literal("null");
                    return null;
                }
                return number();
            }

            void literal(String word) {
                if (!s.startsWith(word, i)) throw err("expected " + word);
                i += word.length();
            }

            Map<String, Object> object() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                ws();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    if (i >= s.length() || s.charAt(i) != '"') throw err("expected object key");
                    String key = string();
                    ws();
                    if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
                    i++;
                    m.put(key, value());
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.length() && s.charAt(i) == '}') {
                        i++;
                        return m;
                    }
                    throw err("expected ',' or '}'");
                }
            }

            List<Object> array() {
                List<Object> l = new ArrayList<>();
                i++;
                ws();
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (i < s.length() && s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.length() && s.charAt(i) == ']') {
                        i++;
                        return l;
                    }
                    throw err("expected ',' or ']'");
                }
            }

            String string() {
                if (i >= s.length() || s.charAt(i) != '"') throw err("expected string");
                i++;
                StringBuilder b = new StringBuilder();
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') return b.toString();
                    if (c != '\\') {
                        b.append(c);
                        continue;
                    }
                    if (i >= s.length()) throw err("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("bad unicode escape");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw err("bad escape \\" + e);
                    }
                }
                throw err("unterminated string");
            }

            Object number() {
                int start = i;
                if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                    else break;
                }
                String t = s.substring(start, i);
                if (t.isEmpty()) throw err("unexpected character '" + s.charAt(start) + "'");
                boolean integral = t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0;
                if (integral) {
                    try {
                        return Long.parseLong(t);
                    } catch (NumberFormatException ignored) {
                        // too large for a long: fall through to double
                    }
                }
                return Double.parseDouble(t);
            }
        }
    }
}
