package dev.msa2ssid.mod;

import com.mojang.blaze3d.platform.NativeImage;
import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Http;
import dev.msa2ssid.core.Skins;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Downloads each account's skin once and registers an 8x8 head texture.
 *
 * The account list asks for heads while it renders, so every fetch goes through one
 * background thread and a failed download is remembered with a growing back-off. A
 * list of accounts whose skins cannot be fetched would otherwise retry on every
 * frame and get the client rate limited by the texture host (HTTP 429).
 */
public final class Heads {

    private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
    private static final Map<String, Identifier> BY_KEY = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    /** uuid -> earliest time it may be fetched again, and how many attempts failed. */
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();

    /** One fetch at a time: a just-imported list must not burst the texture host. */
    private static final ExecutorService FETCHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "msa2ssid-skins");
        thread.setDaemon(true);
        return thread;
    });

    private static final long FIRST_BACKOFF_MS = 30_000;
    private static final long MAX_BACKOFF_MS = 10 * 60_000;

    private Heads() {
    }

    public static Identifier get(Account account) {
        return account.uuid() == null ? null : READY.get(account.uuid());
    }

    /**
     * Registers an arbitrary 8x8 head preview (skin library entries) under a stable
     * key. Must be called on the client thread.
     */
    public static Identifier preview(String key, byte[] headPng) {
        Identifier existing = BY_KEY.get(key);
        if (existing != null) return existing;
        try {
            NativeImage image = NativeImage.read(headPng);
            Identifier id = Identifier.fromNamespaceAndPath("msa2ssid",
                    "preview/" + Integer.toHexString(key.hashCode()));
            Minecraft.getInstance().getTextureManager()
                    .register(id, new DynamicTexture(() -> "msa2ssid preview " + key, image));
            BY_KEY.put(key, id);
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /** Drops the cached icon so the next render picks up a new skin, retrying at once. */
    public static void invalidate(String uuid) {
        if (uuid == null) return;
        READY.remove(uuid);
        RETRY_AT.remove(uuid);
        FAILURES.remove(uuid);
    }

    /** Kicks off a download if this account has a skin and one is not already cached. */
    public static void request(Account account) {
        if (account == null || account.uuid() == null || account.skinUrl() == null) return;
        String uuid = account.uuid();
        if (READY.containsKey(uuid)) return;
        Long retryAt = RETRY_AT.get(uuid);
        if (retryAt != null && System.currentTimeMillis() < retryAt) return;
        if (!PENDING.add(uuid)) return;
        FETCHER.execute(() -> fetch(account));
    }

    private static void fetch(Account account) {
        String uuid = account.uuid();
        try {
            byte[] skin = Http.getBytes(account.skinUrl());
            byte[] head = Skins.headIcon(skin);
            Minecraft.getInstance().execute(() -> {
                try {
                    register(uuid, head);
                } finally {
                    PENDING.remove(uuid);
                }
            });
        } catch (Exception e) {
            PENDING.remove(uuid);
            backOff(uuid, e);
        }
    }

    /** Remembers a failed fetch so the render loop stops asking for it for a while. */
    private static void backOff(String uuid, Exception e) {
        int failures = FAILURES.merge(uuid, 1, Integer::sum);
        long wait = Math.min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS << Math.min(failures - 1, 5));
        RETRY_AT.put(uuid, System.currentTimeMillis() + wait);
        if (failures == 1) {
            System.err.println("[msa2ssid] skin fetch failed, next try in "
                    + (wait / 1000) + "s: " + e.getMessage());
        }
    }

    private static void register(String uuid, byte[] png) {
        try {
            NativeImage image = NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath("msa2ssid", "heads/" + uuid.replace("-", ""));
            Minecraft.getInstance().getTextureManager()
                    .register(id, new DynamicTexture(() -> "msa2ssid head " + uuid, image));
            READY.put(uuid, id);
            RETRY_AT.remove(uuid);
            FAILURES.remove(uuid);
        } catch (Exception e) {
            System.err.println("[msa2ssid] could not register head for " + uuid + ": " + e.getMessage());
        }
    }
}
