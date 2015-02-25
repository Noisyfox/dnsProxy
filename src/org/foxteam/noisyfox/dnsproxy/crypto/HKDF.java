/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Android Sync Client.
 *
 * The Initial Developer of the Original Code is
 * the Mozilla Foundation.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * Jason Voll
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.foxteam.noisyfox.dnsproxy.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * <p/>
 * HMAC-based Extract-and-Expand Key Derivation Function
 * <p/>
 * A standards-compliant implementation of RFC 5869
 * for HMAC-based Key Derivation Function.
 * HMAC uses HMAC SHA256 standard.
 * <p/>
 * see https://github.com/mozilla-services/sync-crypto/blob/master/src/main/java/org/mozilla/android/sync/crypto/HKDF.java
 * and https://github.com/mozilla-services/sync-crypto/blob/master/src/main/java/org/mozilla/android/sync/crypto/Utils.java
 */
public class HKDF {

    private static final byte SALT_DEFAULT[] = bytes("我是小狐狸哈哈哈哈！@#%&^SDF$^^&UJT&@!%~!@*(JKLWER@!td jguu345");
    private static final byte INFO_DEFAULT[] = bytes("NoisyfoxDNSProxy");

    public static byte[] doHKDF(byte[] IKM, int len) {
        byte prk[] = hkdfExtract(SALT_DEFAULT, IKM);
        return hkdfExpand(prk, INFO_DEFAULT, len);
    }

    public static final int BLOCKSIZE = 256 / 8;

    /**
     * Used for conversion in cases in which you *know* the encoding exists.
     */
    public static byte[] bytes(String in) {
        try {
            return in.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    /*
     * Step 1 of RFC 5869
     * Get sha256HMAC Bytes
     * Input: salt (message), IKM (input keyring material)
     * Output: PRK (pseudorandom key)
     */
    public static byte[] hkdfExtract(byte[] salt, byte[] IKM) {
        return digestBytes(IKM, makeHMACHasher(salt));
    }

    /*
     * Step 2 of RFC 5869.
     * Input: PRK from step 1, info, length.
     * Output: OKM (output keyring material).
     */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int len) {

        Mac hmacHasher = makeHMACHasher(prk);

        byte[] T = {};
        byte[] Tn = {};

        int iterations = (int) Math.ceil(((double) len) / ((double) BLOCKSIZE));
        for (int i = 0; i < iterations; i++) {
            Tn = digestBytes(concatAll
                    (Tn, info, hex2Byte(Integer.toHexString(i + 1))), hmacHasher);
            T = concatAll(T, Tn);
        }

        return Arrays.copyOfRange(T, 0, len);
    }

    /*
     * Make HMAC key
     * Input: key (salt)
     * Output: Key HMAC-Key
     */
    public static Key makeHMACKey(byte[] key) {
        if (key.length == 0) {
            key = new byte[BLOCKSIZE];
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    /*
     * Make an HMAC hasher
     * Input: Key hmacKey
     * Ouput: An HMAC Hasher
     */
    public static Mac makeHMACHasher(byte[] key) {
        Mac hmacHasher = null;
        try {
            hmacHasher = Mac.getInstance("hmacSHA256");
            hmacHasher.init(makeHMACKey(key));
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            e.printStackTrace();
        }

        return hmacHasher;
    }

    /*
     * Hash bytes with given hasher
     * Input: message to hash, HMAC hasher
     * Output: hashed byte[].
     */
    public static byte[] digestBytes(byte[] message, Mac hasher) {
        hasher.update(message);
        byte[] ret = hasher.doFinal();
        hasher.reset();
        return ret;
    }

    /*
    * Helper to convert Hex String to Byte Array
    * Input: Hex string
    * Output: byte[] version of hex string
    */
    public static byte[] hex2Byte(String str) {
        if (str.length() % 2 == 1) {
            str = "0" + str;
        }

        byte[] bytes = new byte[str.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer
                    .parseInt(str.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    /*
     * Helper to convert Byte Array to a Hex String
     * Input: byte[] array
     * Output: Hex string
     */
    public static String byte2hex(byte[] b) {

        // String Buffer can be used instead
        String hs = "";
        String stmp = "";

        for (int n = 0; n < b.length; n++) {
            stmp = (java.lang.Integer.toHexString(b[n] & 0XFF));

            if (stmp.length() == 1) {
                hs = hs + "0" + stmp;
            } else {
                hs = hs + stmp;
            }

            if (n < b.length - 1) {
                hs = hs + "";
            }
        }

        return hs;
    }

    /*
     * Helper for array concatenation.
     * Input: At least two byte[]
     * Output: A concatenated version of them
     */
    public static byte[] concatAll(byte[] first, byte[]... rest) {
        int totalLength = first.length;
        for (byte[] array : rest) {
            totalLength += array.length;
        }

        byte[] result = Arrays.copyOf(first, totalLength);
        int offset = first.length;

        for (byte[] array : rest) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

}
