package dev.msa2ssid.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accounts plus the OAuth client id, persisted as one JSON file.
 *
 * The file holds Microsoft refresh tokens, so it is written with owner-only
 * permissions and should never be copied around.
 */
public final class Store {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path path;
    private final List<Account> accounts = new ArrayList<>();
    private String clientId;

    private Store(Path path) {
        this.path = path;
    }

    public static Store load(Path path) {
        Store store = new Store(path);
        if (!Files.isRegularFile(path)) return store;
        try {
            Map<String, Object> root = Json.object(Files.readString(path, StandardCharsets.UTF_8));
            store.clientId = Json.str(root.get("clientId"));
            for (Object o : Json.asArray(root.get("accounts"))) {
                Account a = Account.fromMap(Json.asObject(o));
                if (a.name() != null) store.accounts.add(a);
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt file must not take the game down; start empty and report.
            System.err.println("[msa2ssid] could not read " + path + ": " + e.getMessage());
        }
        return store;
    }

    public Path path() {
        return path;
    }

    public String clientId() {
        return clientId == null || clientId.isBlank() ? null : clientId.trim();
    }

    public void setClientId(String id) {
        this.clientId = id == null ? null : id.trim();
    }

    public List<Account> accounts() {
        return accounts;
    }

    /** Adds the account, or replaces the entry with the same uuid. */
    public void put(Account account) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).uuid() != null && accounts.get(i).uuid().equals(account.uuid())) {
                accounts.set(i, account);
                return;
            }
        }
        accounts.add(account);
    }

    public void remove(Account account) {
        accounts.removeIf(a -> a.uuid() != null && a.uuid().equals(account.uuid()));
    }

    public void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("clientId", clientId == null ? "" : clientId);
        List<Object> list = new ArrayList<>();
        for (Account a : accounts) list.add(a.toMap());
        root.put("accounts", list);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
