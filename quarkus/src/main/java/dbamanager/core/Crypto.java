package dbamanager.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Cifrado de las contraseñas de conexiones (formato Fernet, la misma SECRETS_KEY de 32 bytes en
 * base64 url-safe) y sellos HMAC con esa llave.
 */
@ApplicationScoped
public class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject Settings settings;

    private byte[] key() {
        byte[] k;
        try {
            k = Base64.getUrlDecoder().decode(settings.secretsKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SECRETS_KEY no es base64 url-safe válido");
        }
        if (k.length != 32) throw new IllegalStateException("SECRETS_KEY debe tener 32 bytes en base64 url-safe");
        return k;
    }

    public void checkKey() {
        key();
    }

    public String encrypt(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            byte[] k = key();
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(k, 16, 32), "AES"), new IvParameterSpec(iv));
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer body = ByteBuffer.allocate(1 + 8 + 16 + ct.length);
            body.put((byte) 0x80).putLong(System.currentTimeMillis() / 1000).put(iv).put(ct);
            byte[] signed = body.array();
            byte[] mac = hmac(Arrays.copyOfRange(k, 0, 16), signed);
            return Base64.getUrlEncoder().encodeToString(ByteBuffer.allocate(signed.length + 32).put(signed).put(mac).array());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar", e);
        }
    }

    public String decrypt(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            byte[] k = key();
            byte[] data = Base64.getUrlDecoder().decode(token);
            if (data.length < 57 || data[0] != (byte) 0x80) throw new IllegalStateException("Valor cifrado inválido");
            byte[] signed = Arrays.copyOfRange(data, 0, data.length - 32);
            byte[] mac = Arrays.copyOfRange(data, data.length - 32, data.length);
            if (!MessageDigest.isEqual(mac, hmac(Arrays.copyOfRange(k, 0, 16), signed))) {
                throw new IllegalStateException("No se pudo descifrar: SECRETS_KEY distinta a la usada al guardar");
            }
            byte[] iv = Arrays.copyOfRange(signed, 9, 25);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(k, 16, 32), "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(Arrays.copyOfRange(signed, 25, signed.length)), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descifrar", e);
        }
    }

    /** HMAC-SHA256 en hex con SECRETS_KEY. */
    public String seal(String text) {
        return HexFormat.of().formatHex(hmac(settings.secretsKey.getBytes(StandardCharsets.UTF_8), text.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean sameHex(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Contraseña aleatoria para CREATE USER (se muestra una sola vez y no se guarda). */
    public static String randomPassword() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder("Db");
        for (int i = 0; i < 20; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.append('9').toString();
    }
}
