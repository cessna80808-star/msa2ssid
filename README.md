# msa2ssid

A Minecraft account manager for Fabric 1.21.11 and 26.x, plus a command-line MSA → SSID
converter.

- **Mod** — keep Microsoft accounts in one list: add them with a device-code sign-in, by
  signing in with a login.live.com browser cookie, by pasting a refresh token or SSID, or
  by importing a whole `label:token` set from the clipboard. Switch the running game's
  session, export a fresh SSID, upload skins from a local library, and change an
  account's name through the official API.
- **CLI** — turn an MSA token, refresh token or SSID into a working Minecraft session from
  a terminal.

## Is this safe?

Fair question — the mod handles account tokens, so don't take a jar on trust:

- Every line of it is in this repository. Nothing is obfuscated, and the mod jar is plain
  `javac` output with no libraries bundled inside.
- It talks to Microsoft, Xbox Live and Mojang services and nothing else: `login.live.com`
  (device code and the cookie sign-in), `login.microsoftonline.com`,
  `user.auth.xboxlive.com`, `xsts.auth.xboxlive.com`, `api.minecraftservices.com`,
  `sessionserver.mojang.com`, plus Mojang's skin texture host.
  Searching the source for `https://` lists every endpoint.
- Tokens are written to `config/msa2ssid.json` with owner-only permissions (`0600`), and are
  never sent anywhere except those services.
- `./build-mod.sh 26.3` rebuilds the release jar from source, and the CLI is a single 21 KB
  jar of readable classes — decompile it, or run it with `--help` without a game installed.

If a jar you got from somewhere else behaves differently, don't run it.

## Build

Java 25 and Python 3 (the build fetches the client jars it compiles against) are required.

```bash
./build-mod.sh 26.3     # one Minecraft version, or "./build-mod.sh" for 1.21.11, 26.1.2, 26.2 and 26.3
./build.sh              # CLI -> dist/msa2ssid.jar
```

There is no Gradle or Loom step. 26.x ships unobfuscated, so it is compiled straight
against the client jar. 1.21.11 and older ship obfuscated, so those builds also fetch
Mojang's official mappings and a remapper into `.cache/`, compile against the
deobfuscated client and Fabric API, rename the handful of API names that only 26.x
spells differently, and remap the finished classes to intermediary — the namespace
Fabric Loader resolves mods in. The first build of a 1.21.x version takes a minute
longer; later builds reuse the cache.

## Install

1. Fabric Loader 0.19.3 or newer, plus Fabric API, for Minecraft 26.1.2, 26.2 or 26.3
   (1.21.11 needs Loader 0.17.0 or newer).
2. Drop `account-manager-<version>.jar` into `mods/`.
3. Press **Right Shift** in game, or click **Accounts** on the title screen.

## Using the mod

| Button | What it does |
| --- | --- |
| Add account | Microsoft device-code sign-in; the refresh token is stored so the account renews itself |
| Cookie | Signs in from a login.live.com browser cookie: paste it, press Enter |
| Paste token | One token or cookie, or a whole `label:token` clipboard set — one account per line, and a bad line never stops the rest. A pasted `cookies.txt` export is read as one account, keeping its tab-separated columns |
| SSID | Refreshes the selected account and copies its Minecraft access token |
| Activate | Switches the running game to the selected account, so the next server join uses it |
| Skin | Uploads a skin to the selected account, with a reusable local library |
| Rename | Changes the account's name through the official API |
| Remove | Forgets the account |

Accounts live in `config/msa2ssid.json` with owner-only permissions. A token the services
reject is removed automatically, so the list does not fill up with dead entries.

### Signing in with a cookie

**Cookie** signs an account in from a browser session that is already signed in, instead
of showing a device code:

1. Sign in at <https://login.live.com> in a browser.
2. Open the browser's developer tools, find a request to `login.live.com`, and copy its
   `Cookie` request header (a `MSPAuth=...; MSPProf=...` pair list, a
   `__Host-MSAAUTHP=...` header, or a Netscape `cookies.txt` export all work).
3. Paste it into **Cookie** and press **Enter** — that is the whole flow; there is no
   browser to open and nothing else to confirm.

The exchange is the launcher's own: the cookie answers the OAuth authorize request, and
the code it redirects back with becomes a normal token pair. The account is then stored
with a refresh token, exactly like a device-code sign-in, so it keeps renewing itself.
An account that requires a passkey cannot sign in this way — use **Add account** for it.

A cookie is a live session for the account: treat a pasted cookie like a password and
only ever use your own.

`Activate` swaps only the login. The skin and chat-signing key come from the account the
game was launched with until it restarts.

## Using the CLI

```bash
java -jar msa2ssid.jar --help
java -jar msa2ssid.jar 'M.C000_EXAMPLE...'              # token or SSID -> session
java -jar msa2ssid.jar --ssid 'eyJ...' --join-server <hash>
```

## Notes

- Only use tokens for accounts you own.
- Skins and names go through the official services; nothing is spoofed client-side.
- Refresh tokens rotate, and the mod saves each new one it receives.
