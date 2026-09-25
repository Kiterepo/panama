package org.leo.server.panama.vpn.security.wrapper;

import org.leo.server.panama.vpn.security.chipher.Cipher;
import org.leo.server.panama.vpn.security.chipher.KeyHelper;

import java.util.Arrays;

public class CipherWrapper extends Wrapper {
    private final Cipher encipher;
    private final Cipher decipher;
    private byte[] encipherIv;
    private byte[] decipherIv;
    private int decipherIvBytes;
    private boolean decipherInitialized;

    public CipherWrapper(Cipher encipher, Cipher decipher) {
        if (encipher.getClass() != decipher.getClass())
            throw new RuntimeException("cipher type not match");

        this.encipher = encipher;
        this.decipher = decipher;
    }

    @Override
    public byte[] wrap(final byte[] bytes) {
        if (encipherIv == null) {
            int ivLength = encipher.getIVLength();
            this.encipherIv = KeyHelper.generateRandomBytes(ivLength);
            encipher.init(true, encipherIv);
            byte[] encryptedBytes = new byte[ivLength + bytes.length];
            System.arraycopy(encipherIv, 0, encryptedBytes, 0, ivLength);
            System.arraycopy(encipher.encrypt(bytes), 0, encryptedBytes, ivLength, bytes.length);
            return encryptedBytes;
        }
        return encipher.encrypt(bytes);
    }

    @Override
    public byte[] unwrap(final byte[] bytes) {
        int offset = 0;
        if (!decipherInitialized) {
            int ivLength = decipher.getIVLength();
            if (decipherIv == null) {
                decipherIv = new byte[ivLength];
            }
            offset = Math.min(bytes.length, ivLength - decipherIvBytes);
            System.arraycopy(bytes, 0, decipherIv, decipherIvBytes, offset);
            decipherIvBytes += offset;
            if (decipherIvBytes < ivLength) {
                return new byte[0];
            }
            decipher.init(false, decipherIv);
            decipherInitialized = true;
        }
        return decipher.decrypt(offset == 0 ? bytes : Arrays.copyOfRange(bytes, offset, bytes.length));
    }
}
