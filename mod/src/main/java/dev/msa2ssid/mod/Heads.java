package dev.msa2ssid.mod;

import com.mojang.blaze3d.platform.NativeImage;
import dev.msa2ssid.core.Account;
import dev.msa2ssid.core.Http;
import dev.msa2ssid.core.Skins;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Downloads each account's skin once and registers an 8x8 head texture. */
public final class Heads {

    private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
    private static final Map<String, Identifier> BY_KEY = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

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

    /** Drops the cached icon so the next render picks up a new skin. */
    public static void invalidate(String uuid) {
        if (uuid != null) READY.remove(uuid);
    }

    /** Kicks off a download if this account has a skin and one is not already cached. */
    public static void request(Account account) {
        if (account == null || account.uuid() == null || account.skinUrl() == null) return;
        if (READY.containsKey(account.uuid())) return;
        if (!PENDING.add(account.uuid())) return;

        CompletableFuture.runAsync(() -> {
            try {
                byte[] skin = Http.getBytes(account.skinUrl());
                byte[] head = Skins.headIcon(skin);
                Minecraft.getInstance().execute(() -> register(account.uuid(), head));
            } catch (Exception e) {
                System.err.println("[msa2ssid] skin for " + account.name() + " failed: " + e.getMessage());
            } finally {
                PENDING.remove(account.uuid());
            }
        });
    }

    private static void register(String uuid, byte[] png) {
        try {
            NativeImage image = NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath("msa2ssid", "heads/" + uuid.replace("-", ""));
            Minecraft.getInstance().getTextureManager()
                    .register(id, new DynamicTexture(() -> "msa2ssid head " + uuid, image));
            READY.put(uuid, id);
        } catch (Exception e) {
            System.err.println("[msa2ssid] could not register head for " + uuid + ": " + e.getMessage());
        }
    }
}
