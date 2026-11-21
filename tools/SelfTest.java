import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Auth;
import dev.msa2ssid.core.Bans;
import dev.msa2ssid.core.Http;
import dev.msa2ssid.core.Skins;
import dev.msa2ssid.core.Store;
import dev.msa2ssid.core.Throttle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Exercises the parts of the core that do not need a Minecraft client. */
public final class SelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        storeRoundTrip();
        headIcon();
        legacySkin();
        skinValidation();
        multipartBody();
        skinLibrary();
        tokenParsing();
        deadTokenIsRejected();
        tokenListParsing();
        banAndRateLimit();
        sessionToAccount();
        deviceCodeReachable();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  PASS  " + name);
        } else {
            failures++;
            System.out.println("  FAIL  " + name + (detail == null ? "" : " -> " + detail));
        }
    }

    private static void storeRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("msa2ssid-test");
        Path file = dir.resolve("msa2ssid.json");

        Store store = Store.load(file);
        store.setClientId("11111111-2222-3333-4444-555555555555");
        store.put(new Account("TestUser", "069a79f4-44e9-4726-a5be-fca90e38aaf5", "2535",
                "mctoken", System.currentTimeMillis() / 1000 + 3600, "refreshtoken",
                "https://example.invalid/skin.png", 1));
        store.save();

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        check("store file is owner-only", perms.equals(Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE)), perms.toString());

        Store reloaded = Store.load(file);
        check("client id survives round trip", "11111111-2222-3333-4444-555555555555".equals(reloaded.clientId()),
                reloaded.clientId());
        check("account survives round trip", reloaded.accounts().size() == 1
                && "TestUser".equals(reloaded.accounts().get(0).name()), "" + reloaded.accounts());
        check("live session detected", reloaded.accounts().get(0).hasLiveSession(), "");

        Account updated = reloaded.accounts().get(0).withProfile("Renamed", null, null, null);
        reloaded.put(updated);
        check("put replaces by uuid", reloaded.accounts().size() == 1
                && "Renamed".equals(reloaded.accounts().get(0).name()), "" + reloaded.accounts());

        reloaded.remove(updated);
        check("remove works", reloaded.accounts().isEmpty(), "");

        // A corrupt file must not throw.
        Files.writeString(file, "{ this is not json");
        Store corrupt = Store.load(file);
        check("corrupt store loads empty", corrupt.accounts().isEmpty(), "");
    }

    private static void headIcon() throws Exception {
        byte[] head = Skins.headIcon(syntheticSkin(64, 64));
        BufferedImage icon = ImageIO.read(new java.io.ByteArrayInputStream(head));
        check("head icon is 8x8", icon != null && icon.getWidth() == 8 && icon.getHeight() == 8,
                icon == null ? "unreadable" : icon.getWidth() + "x" + icon.getHeight());
        // Face pixel (8,8) was painted pure red, hat layer transparent.
        check("face pixel copied", (icon.getRGB(0, 0) & 0xFFFFFF) == 0xFF0000,
                Integer.toHexString(icon.getRGB(0, 0)));
    }

    private static void legacySkin() throws Exception {
        byte[] head = Skins.headIcon(syntheticSkin(64, 32));
        BufferedImage icon = ImageIO.read(new java.io.ByteArrayInputStream(head));
        check("legacy 64x32 skin still yields 8x8", icon != null && icon.getWidth() == 8, "");
    }

    private static void skinValidation() {
        check("64x64 skin accepted", accepts(64, 64), "");
        check("64x32 skin accepted", accepts(64, 32), "");
        check("128x128 rejected", !accepts(128, 128), "");
        check("non-image rejected", rejects(new byte[]{1, 2, 3, 4}), "");
    }

    private static boolean accepts(int w, int h) {
        try {
            Skins.validateSkin(syntheticSkin(w, h > 32 ? h : 32));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean rejects(byte[] data) {
        try {
            Skins.validateSkin(data);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static void tokenListParsing() {
        String list = "# exported accounts\n"
                + "alpha:M.C000_EXAMPLE.0.U.Example.-abcdefghijklmnop\n"
                + "\n"
                + "beta:  M.C000_EXAMPLE.0.U.Example.-zyxwvutsrqponmlk\n"
                + "M.C000_EXAMPLE.0.U.Example.-bare-token-with-no-label-1234\n"
                + "short:abc\n";
        var lines = dev.msa2ssid.core.Auth.parseTokenList(list);
        check("list keeps every real line", lines.size() == 3, "" + lines.size());
        check("label parsed", "alpha".equals(lines.get(0).label()), "" + lines.get(0).label());
        check("label trimmed from token", lines.get(0).token().startsWith("M.C000"), lines.get(0).token());
        check("spaces after the colon survive", lines.get(1).token().startsWith("M.C000"), lines.get(1).token());
        check("bare token has no label", lines.get(2).label() == null, "" + lines.get(2).label());
        check("blank and comment lines dropped", lines.stream().noneMatch(l -> l.token().startsWith("#")), "");
        check("too-short line ignored", lines.stream().noneMatch(l -> l.token().equals("abc")), "");
        check("empty clipboard is empty", dev.msa2ssid.core.Auth.parseTokenList("").isEmpty(), "");

        String full = "alpha:M.C000_EXAMPLE.0.U.Example." + "a".repeat(40) + "\n"
                + "beta:M.C000_EXAMPLE.0.U.Example." + "b".repeat(40) + "\n"
                + "gamma:M.C000_EXAMPLE.0.U.Example." + "c".repeat(40) + "\n"
                + "delta:M.C000_EXAMPLE.0.U.Example." + "d".repeat(40) + "\n";
        check("whole set of four parses", dev.msa2ssid.core.Auth.parseTokenList(full).size() == 4, "");
        check("no label on a bare token line",
                dev.msa2ssid.core.Auth.parseTokenList("M.C000_EXAMPLE.0.U.Example." + "a".repeat(40))
                        .get(0).label() == null, "");
    }

    /** Hypixel ban detection and the import rate-limit policy. */
    private static void banAndRateLimit() {
        check("hypixel host recognized", Bans.isHypixel("mc.hypixel.net"), "");
        check("hypixel host with a port recognized", Bans.isHypixel("hypixel.net:25565"), "");
        check("other host is not hypixel", !Bans.isHypixel("play.example.net"), "");
        check("lookalike host is not hypixel", !Bans.isHypixel("not-hypixel.net"), "");
        check("permanent ban recognized", Bans.looksBanned(
                "You are permanently banned from this server! Reason: Cheating\n"
                        + "Find out more here: https://hypixel.net/appeal"), "");
        check("temporary ban recognized",
                Bans.looksBanned("You are temporarily banned for 29d 23h 59m from this server!"), "");
        check("security block recognized",
                Bans.looksBanned("Your account is currently blocked from joining this server."), "");
        check("plain kick is not a ban", !Bans.looksBanned("You were kicked from this server!"), "");
        check("missing reason is not a ban", !Bans.looksBanned(null), "");

        check("backoff grows", Throttle.waitMillis(0, 0) == 5000 && Throttle.waitMillis(1, 0) == 10000
                && Throttle.waitMillis(2, 0) == 20000, "");
        check("backoff honours Retry-After", Throttle.waitMillis(0, 7) == 7000, "");
        check("backoff is capped", Throttle.waitMillis(0, 9999) == 60000, "");
        check("429 is a rate limit", Throttle.rateLimited(new Http.ApiException("too many", 429, "{}")), "");
        check("401 is not a rate limit", !Throttle.rateLimited(new Http.ApiException("no", 401, "{}")), "");
        check("Retry-After survives the exception",
                Throttle.retryAfter(new Http.ApiException("slow down", 429, "{}", 7)) == 7, "");
    }

    private static void sessionToAccount() {
        long before = java.time.Instant.now().getEpochSecond();
        var session = new dev.msa2ssid.core.Auth.Session(
                "someone", "00000000-0000-0000-0000-000000000001", "12345",
                "mc-token", 3600, "https://example.invalid/skin.png");
        var account = dev.msa2ssid.core.Account.fromSession(session, "refresh");

        check("session maps to account",
                "someone".equals(account.name()) && "refresh".equals(account.msRefreshToken())
                        && "mc-token".equals(account.mcAccessToken()), "");
        check("session copied the profile",
                "00000000-0000-0000-0000-000000000001".equals(account.uuid())
                        && "12345".equals(account.xuid()), "");
        check("session expiry comes from expires_in",
                account.mcExpiresAtEpochSec() >= before + 3600, "" + account.mcExpiresAtEpochSec());
        check("missing name gets a placeholder",
                "(unnamed)".equals(dev.msa2ssid.core.Account.fromSession(
                        new dev.msa2ssid.core.Auth.Session(null, null, null, "t", 0, null), null).name()), "");
    }

    private static void deadTokenIsRejected() {
        dev.msa2ssid.core.Account dead = new dev.msa2ssid.core.Account(
                "dead", null, null, null, 0, null, null, 0);
        boolean rejected = false;
        try {
            dev.msa2ssid.core.Auth.ensureSession(null, dead);
        } catch (dev.msa2ssid.core.Auth.TokenRejectedException e) {
            rejected = true;
        } catch (Exception ignored) {
            // any other failure must not be treated as a dead token
        }
        check("dead token is reported as rejected", rejected, "");
    }

    private static void tokenParsing() {
        check("refresh token recognized", dev.msa2ssid.core.Auth.looksLikeRefreshToken("M.C000_EXAMPLE.0.U.-abc"), "");
        check("0.A token recognized", dev.msa2ssid.core.Auth.looksLikeRefreshToken("0.AXoAabc"), "");
        check("SSID is not a refresh token", !dev.msa2ssid.core.Auth.looksLikeRefreshToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"), "");
        check("SSID shape detected", dev.msa2ssid.core.Auth.looksLikeJwt("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"), "");
        check("compact ticket is not a jwt", !dev.msa2ssid.core.Auth.looksLikeJwt("M.C000_EXAMPLE"), "");
        check("pasted line breaks stripped",
                dev.msa2ssid.core.Auth.cleanToken("  eyJhbGci\n  OiJIUzI1NiJ9  ").equals("eyJhbGciOiJIUzI1NiJ9"),
                dev.msa2ssid.core.Auth.cleanToken("  eyJhbGci\n  OiJIUzI1NiJ9  "));
        check("quotes stripped",
                dev.msa2ssid.core.Auth.cleanToken("\"eyJhbGciOiJIUzI1NiJ9\"").equals("eyJhbGciOiJIUzI1NiJ9"), "");
        check("d= prefix stripped",
                dev.msa2ssid.core.Auth.cleanToken("d=eyJhbGciOiJIUzI1NiJ9").equals("eyJhbGciOiJIUzI1NiJ9"), "");
        check("empty token rejected", dev.msa2ssid.core.Auth.cleanToken("   \n ").isEmpty(), "");
    }

    private static void skinLibrary() throws Exception {
        Path dir = Files.createTempDirectory("msa2ssid-lib").resolve("skins");
        dev.msa2ssid.core.SkinLibrary library = new dev.msa2ssid.core.SkinLibrary(dir);

        check("empty library loads", library.load().isEmpty(), "");
        check("library folder created", Files.isDirectory(dir), "");

        library.add("My Skin!!", syntheticSkin(64, 64));
        check("added skin is listed", library.load().size() == 1, "");
        check("added file name is sanitized",
                library.load().get(0).name().equals("My_Skin__.png"), library.load().get(0).name());
        check("entry carries an 8x8 preview",
                ImageIO.read(new java.io.ByteArrayInputStream(library.load().get(0).headPng())).getWidth() == 8, "");

        Files.write(dir.resolve("broken.png"), new byte[]{0, 1, 2});
        Files.write(dir.resolve("notes.txt"), "hello".getBytes());
        check("invalid files are skipped", library.load().size() == 1, "" + library.load().size());

        boolean rejected = false;
        try {
            library.add("big", syntheticSkin(128, 128));
        } catch (Exception e) {
            rejected = true;
        }
        check("128x128 refused by the library", rejected, "");
    }

    private static void multipartBody() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        Http.Multipart mp = Http.multipart(java.util.Map.of("variant", "classic"),
                "file", "skin.png", "image/png", png);
        String head = new String(mp.body(), java.nio.charset.StandardCharsets.ISO_8859_1);

        check("multipart content type carries boundary",
                mp.contentType().startsWith("multipart/form-data; boundary="), mp.contentType());
        String boundary = mp.contentType().substring(mp.contentType().indexOf('=') + 1);
        check("variant field present", head.contains("name=\"variant\"") && head.contains("classic"), "");
        check("file part present", head.contains("name=\"file\"; filename=\"skin.png\""), "");
        check("file content type present", head.contains("Content-Type: image/png"), "");
        check("file bytes embedded", head.contains("\u0089PNG"), "");
        check("body terminated", head.stripTrailing().endsWith("--" + boundary + "--"), "");
    }

    /** Paints the face area red and leaves the rest transparent. */
    private static byte[] syntheticSkin(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                img.setRGB(x, y, 0xFFFF0000);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Confirms the device-code endpoint is reachable and its errors are parsed. */
    private static void deviceCodeReachable() {
        try {
            Auth.startDeviceCode("00000000-0000-0000-0000-000000000000");
            check("device code endpoint reachable", false, "unexpected success with a bogus client id");
        } catch (Http.ApiException e) {
            boolean parsed = e.getMessage() != null && e.getMessage().contains("HTTP");
            check("device code endpoint reachable, error parsed", parsed, e.getMessage());
            System.out.println("        (" + e.getMessage() + ")");
        } catch (Exception e) {
            check("device code endpoint reachable", false, e.toString());
        }
    }
}
