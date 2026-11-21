package dev.msa2ssid.mod;

import dev.msa2ssid.core.Account;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Points the running client at another account.
 *
 * The client asks {@code Minecraft.getUser()} for the profile id and access token
 * every time it joins a server, so replacing that one field is enough to play as a
 * different account — no launcher switch, no restart. The field is final and has no
 * setter, hence reflection; 26.x ships unobfuscated and the field is found by type,
 * so a rename cannot break it.
 *
 * Only the login is swapped. The skin and the chat-signing key come from the
 * {@code userApiService} built at launch, so those (and the name on the title screen)
 * stay with the account the game started with until it restarts.
 */
final class SessionInject {

    private SessionInject() {
    }

    /** True when the client is currently logging in as this uuid. */
    static boolean isActive(String uuid) {
        if (uuid == null) return false;
        User user = Minecraft.getInstance().getUser();
        if (user == null || user.getProfileId() == null) return false;
        return Account.sameUuid(user.getProfileId().toString(), uuid);
    }

    /** Makes the account the client's session. Call on the render thread. */
    static void apply(Account account) throws Exception {
        if (account.mcAccessToken() == null || account.mcAccessToken().isBlank()) {
            throw new IllegalArgumentException("it has no session");
        }
        if (account.name() == null || account.name().isBlank()) {
            throw new IllegalArgumentException("it has no name");
        }

        User user = new User(account.name(), profileId(account.uuid()), account.mcAccessToken(),
                Optional.ofNullable(account.xuid()), Optional.empty());

        Field field = null;
        for (Field f : Minecraft.class.getDeclaredFields()) {
            if (f.getType() == User.class) {
                field = f;
                break;
            }
        }
        if (field == null) throw new IllegalStateException("this build has no user field");

        field.setAccessible(true);
        field.set(Minecraft.getInstance(), user);
    }

    /** Accepts a 32-character hex uuid and its dashed form. */
    private static UUID profileId(String raw) {
        String digits = raw == null ? "" : raw.replace("-", "");
        if (digits.length() != 32) throw new IllegalArgumentException("it has no uuid");
        return new UUID(Long.parseUnsignedLong(digits.substring(0, 16), 16),
                Long.parseUnsignedLong(digits.substring(16), 16));
    }
}
