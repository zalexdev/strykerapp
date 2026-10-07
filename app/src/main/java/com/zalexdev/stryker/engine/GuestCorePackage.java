package com.zalexdev.stryker.engine;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class GuestCorePackage {

    public static final String ASSET = "rootless/stryker-guest-core.tar.enc";

    private static final byte[] MAGIC = {'S', 'T', 'R', 'K', 'C', 'O', 'R', 'E'};
    private static final int VERSION = 1;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KDF_ROUNDS = 50000;
    private static final char[] SECRET = {'s', 't', 'r', 'y', 'k', 'e', 'r', 't', 'o', 'p'};

    private GuestCorePackage() {
    }

    public static void extractTo(Context ctx, File dest) throws IOException {
        byte[] container;
        InputStream in = ctx.getAssets().open(ASSET);
        try {
            container = readAll(in);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }

        int header = MAGIC.length + 1 + SALT_LEN + IV_LEN;
        if (container.length <= header) {
            throw new IOException("guest core package is truncated");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (container[i] != MAGIC[i]) {
                throw new IOException("guest core package header is not recognised");
            }
        }
        int version = container[MAGIC.length] & 0xFF;
        if (version != VERSION) {
            throw new IOException("guest core package version " + version + " is not supported");
        }

        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(container, MAGIC.length + 1, salt, 0, SALT_LEN);
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(container, MAGIC.length + 1 + SALT_LEN, iv, 0, IV_LEN);

        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(salt), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            plain = cipher.doFinal(container, header, container.length - header);
        } catch (Exception e) {
            throw new IOException("guest core package could not be opened: " + e, e);
        }

        FileOutputStream out = new FileOutputStream(dest);
        try {
            out.write(plain);
            out.flush();
            out.getFD().sync();
        } finally {
            try { out.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] derive(byte[] salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt);
        md.update(new String(SECRET).getBytes("UTF-8"));
        byte[] key = md.digest();
        for (int i = 1; i < KDF_ROUNDS; i++) {
            md.reset();
            md.update(key);
            md.update(salt);
            key = md.digest();
        }
        return key;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream(1 << 19);
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) != -1) sink.write(buf, 0, r);
        return sink.toByteArray();
    }
}
