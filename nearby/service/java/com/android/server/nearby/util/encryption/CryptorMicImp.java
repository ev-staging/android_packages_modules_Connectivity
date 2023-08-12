/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.nearby.util.encryption;

import static com.android.server.nearby.NearbyService.TAG;

import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.nearby.util.ArrayUtils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MIC encryption and decryption for {@link android.nearby.BroadcastRequest#PRESENCE_VERSION_V1}
 * advertisement
 */
public class CryptorMicImp extends Cryptor {

    public static final int MIC_LENGTH = 16;

    private static final String ENCRYPT_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES;
    private static final byte[] AES_KEY_INFO_BYTES = "Unsigned Section AES key".getBytes(
            StandardCharsets.US_ASCII);
    private static final byte[] ADV_NONCE_INFO_BYTES_SALT_DE = "Unsigned Section IV".getBytes(
            StandardCharsets.US_ASCII);
    private static final byte[] ADV_NONCE_INFO_BYTES_ENCRYPTION_INFO_DE =
            "V1 derived salt".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] METADATA_KEY_HMAC_KEY_INFO_BYTES =
            "Unsigned Section metadata key HMAC key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MIC_HMAC_KEY_INFO_BYTES = "Unsigned Section HMAC key".getBytes(
            StandardCharsets.US_ASCII);
    private static final int AES_KEY_SIZE = 16;
    private static final int ADV_NONCE_SIZE_SALT_DE = 16;
    private static final int ADV_NONCE_SIZE_ENCRYPTION_INFO_DE = 12;
    private static final int HMAC_KEY_SIZE = 32;

    // Lazily instantiated when {@link #getInstance()} is called.
    @Nullable
    private static CryptorMicImp sCryptor;

    private CryptorMicImp() {
    }

    /** Returns an instance of CryptorImpV1. */
    public static CryptorMicImp getInstance() {
        if (sCryptor == null) {
            sCryptor = new CryptorMicImp();
        }
        return sCryptor;
    }

    /**
     * Generate the meta data encryption key tag
     * @param metadataEncryptionKey used as identity
     * @param keySeed authenticity key saved in local and shared credential
     * @return bytes generated by hmac or {@code null} when there is an error
     */
    @Nullable
    public static byte[] generateMetadataEncryptionKeyTag(byte[] metadataEncryptionKey,
            byte[] keySeed) {
        try {
            byte[] metadataKeyHmacKey = generateMetadataKeyHmacKey(keySeed);
            return Cryptor.generateHmac(/* algorithm= */ HMAC_SHA256_ALGORITHM, /* input= */
                    metadataEncryptionKey, /* key= */ metadataKeyHmacKey);
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Failed to generate Metadata encryption key tag.", e);
            return null;
        }
    }

    /**
     * @param salt from the 2 bytes Salt Data Element
     */
    @Nullable
    public static byte[] generateAdvNonce(byte[] salt) throws GeneralSecurityException {
        return Cryptor.computeHkdf(
                /* macAlgorithm= */ HMAC_SHA256_ALGORITHM,
                /* ikm = */ salt,
                /* salt= */ NP_HKDF_SALT,
                /* info= */ ADV_NONCE_INFO_BYTES_SALT_DE,
                /* size= */ ADV_NONCE_SIZE_SALT_DE);
    }

    /** Generates the 12 bytes nonce with salt from the 2 bytes Salt Data Element */
    @Nullable
    public static byte[] generateAdvNonce(byte[] salt, int deIndex)
            throws GeneralSecurityException {
        // go/nearby-specs-working-doc
        // Indices are encoded as big-endian unsigned 32-bit integers, starting at 1.
        // Index 0 is reserved
        byte[] indexBytes = new byte[4];
        indexBytes[3] = (byte) deIndex;
        byte[] info =
                ArrayUtils.concatByteArrays(ADV_NONCE_INFO_BYTES_ENCRYPTION_INFO_DE, indexBytes);
        return Cryptor.computeHkdf(
                /* macAlgorithm= */ HMAC_SHA256_ALGORITHM,
                /* ikm = */ salt,
                /* salt= */ NP_HKDF_SALT,
                /* info= */ info,
                /* size= */ ADV_NONCE_SIZE_ENCRYPTION_INFO_DE);
    }

    @Nullable
    @Override
    public byte[] encrypt(byte[] input, byte[] iv, byte[] keySeed) {
        if (input == null || iv == null || keySeed == null) {
            return null;
        }
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            Log.e(TAG, "Failed to encrypt with secret key.", e);
            return null;
        }

        byte[] aesKey;
        try {
            aesKey = generateAesKey(keySeed);
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Encryption failed because failed to generate the AES key.", e);
            return null;
        }
        if (aesKey == null) {
            Log.i(TAG, "Failed to generate the AES key.");
            return null;
        }
        SecretKey secretKey = new SecretKeySpec(aesKey, ENCRYPT_ALGORITHM);
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Log.e(TAG, "Failed to initialize cipher.", e);
            return null;

        }
        try {
            return cipher.doFinal(input);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Log.e(TAG, "Failed to encrypt with secret key.", e);
            return null;
        }
    }

    @Nullable
    @Override
    public byte[] decrypt(byte[] encryptedData, byte[] iv, byte[] keySeed) {
        if (encryptedData == null || iv == null || keySeed == null) {
            return null;
        }

        Cipher cipher;
        try {
            cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            Log.e(TAG, "Failed to get cipher instance.", e);
            return null;
        }
        byte[] aesKey;
        try {
            aesKey = generateAesKey(keySeed);
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Decryption failed because failed to generate the AES key.", e);
            return null;
        }
        SecretKey secretKey = new SecretKeySpec(aesKey, ENCRYPT_ALGORITHM);
        try {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Log.e(TAG, "Failed to initialize cipher.", e);
            return null;
        }

        try {
            return cipher.doFinal(encryptedData);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Log.e(TAG, "Failed to decrypt bytes with secret key.", e);
            return null;
        }
    }

    @Override
    @Nullable
    public byte[] sign(byte[] data, byte[] key) {
        byte[] res = generateHmacTag(data, key);
        return res;
    }

    @Override
    public int getSignatureLength() {
        return MIC_LENGTH;
    }

    @Override
    public boolean verify(byte[] data, byte[] key, byte[] signature) {
        return Arrays.equals(sign(data, key), signature);
    }

    /**
     * Generates a 16 bytes HMAC tag. This is used for decryptor to verify if the computed HMAC tag
     * is equal to HMAC tag in advertisement to see data integrity.
     *
     * @param input   concatenated advertisement UUID, header, section header, derived salt, and
     *                section content
     * @param keySeed the MIC HMAC key is calculated using the derived key
     * @return the first 16 bytes of HMAC-SHA256 result
     */
    @Nullable
    @VisibleForTesting
    byte[] generateHmacTag(byte[] input, byte[] keySeed) {
        try {
            if (input == null || keySeed == null) {
                return null;
            }
            byte[] micHmacKey = generateMicHmacKey(keySeed);
            byte[] hmac = Cryptor.generateHmac(/* algorithm= */ HMAC_SHA256_ALGORITHM, /* input= */
                    input, /* key= */ micHmacKey);
            if (ArrayUtils.isEmpty(hmac)) {
                return null;
            }
            return Arrays.copyOf(hmac, MIC_LENGTH);
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Failed to generate mic hmac key.", e);
            return null;
        }
    }

    @Nullable
    private static byte[] generateAesKey(byte[] keySeed) throws GeneralSecurityException {
        return Cryptor.computeHkdf(
                /* macAlgorithm= */ HMAC_SHA256_ALGORITHM,
                /* ikm = */ keySeed,
                /* salt= */ NP_HKDF_SALT,
                /* info= */ AES_KEY_INFO_BYTES,
                /* size= */ AES_KEY_SIZE);
    }

    private static byte[] generateMetadataKeyHmacKey(byte[] keySeed)
            throws GeneralSecurityException {
        return generateHmacKey(keySeed, METADATA_KEY_HMAC_KEY_INFO_BYTES);
    }

    private static byte[] generateMicHmacKey(byte[] keySeed) throws GeneralSecurityException {
        return generateHmacKey(keySeed, MIC_HMAC_KEY_INFO_BYTES);
    }

    private static byte[] generateHmacKey(byte[] keySeed, byte[] info)
            throws GeneralSecurityException {
        return Cryptor.computeHkdf(
                /* macAlgorithm= */ HMAC_SHA256_ALGORITHM,
                /* ikm = */ keySeed,
                /* salt= */ NP_HKDF_SALT,
                /* info= */ info,
                /* size= */ HMAC_KEY_SIZE);
    }
}
