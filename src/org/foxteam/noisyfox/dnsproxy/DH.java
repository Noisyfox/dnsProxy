package org.foxteam.noisyfox.dnsproxy;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Created by Noisyfox on 2015/2/24.
 * Diffie-Hellman 密钥交换类，密钥长度2048位
 */
public class DH {
    private static final int MODP_2048_I[] = {0x0, // 保证正数
            0xFFFFFFFF, 0xFFFFFFFF, 0xC90FDAA2, 0x2168C234, 0xC4C6628B, 0x80DC1CD1,
            0x29024E08, 0x8A67CC74, 0x020BBEA6, 0x3B139B22, 0x514A0879, 0x8E3404DD,
            0xEF9519B3, 0xCD3A431B, 0x302B0A6D, 0xF25F1437, 0x4FE1356D, 0x6D51C245,
            0xE485B576, 0x625E7EC6, 0xF44C42E9, 0xA637ED6B, 0x0BFF5CB6, 0xF406B7ED,
            0xEE386BFB, 0x5A899FA5, 0xAE9F2411, 0x7C4B1FE6, 0x49286651, 0xECE45B3D,
            0xC2007CB8, 0xA163BF05, 0x98DA4836, 0x1C55D39A, 0x69163FA8, 0xFD24CF5F,
            0x83655D23, 0xDCA3AD96, 0x1C62F356, 0x208552BB, 0x9ED52907, 0x7096966D,
            0x670C354E, 0x4ABC9804, 0xF1746C08, 0xCA18217C, 0x32905E46, 0x2E36CE3B,
            0xE39E772C, 0x180E8603, 0x9B2783A2, 0xEC07A28F, 0xB5C55DF0, 0x6F4C52C9,
            0xDE2BCBF6, 0x95581718, 0x3995497C, 0xEA956AE5, 0x15D22618, 0x98FA0510,
            0x15728E5A, 0x8AACAA68, 0xFFFFFFFF, 0xFFFFFFFF};

    private static final BigInteger MODP_2048;
    private static final BigInteger MODP_2048_M2;
    private static final BigInteger G = BigInteger.valueOf(2L);
    private static final int MAX_LENGTH = 2048;

    static {
        byte modp_2048_b[] = new byte[MODP_2048_I.length * 4];
        int i = 0;
        for (int v : MODP_2048_I) {
            modp_2048_b[i++] = (byte) ((v >> 24) & 0xFF);
            modp_2048_b[i++] = (byte) ((v >> 16) & 0xFF);
            modp_2048_b[i++] = (byte) ((v >> 8) & 0xFF);
            modp_2048_b[i++] = (byte) (v & 0xFF);
        }
        MODP_2048 = new BigInteger(modp_2048_b);
        MODP_2048_M2 = MODP_2048.subtract(BigInteger.valueOf(2L));
    }

    private final int mLength;
    private final SecureRandom mSecureRandom;

    private BigInteger mPrivateKey = null;
    private BigInteger mPublicKey = null;

    public DH(int length, SecureRandom secureRandom) {
        if (length <= 0 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("length must between 1 and " + MAX_LENGTH);
        }

        mLength = length;
        mSecureRandom = secureRandom;
    }

    public void generateKeyPair() {
        mPrivateKey = null;
        mPublicKey = null;
        // 创建私钥
        do {
            mPrivateKey = BigInteger.probablePrime(mLength, mSecureRandom);
        } while (mPrivateKey.compareTo(BigInteger.ONE) < 0 || mPrivateKey.compareTo(MODP_2048_M2) > 0);

        // 创建公钥
        mPublicKey = G.modPow(mPrivateKey, MODP_2048);
    }

    public BigInteger getPublicKey() {
        return mPublicKey;
    }

    public BigInteger getPrivateKey() {
        return mPrivateKey;
    }

    public static BigInteger calculateS(BigInteger privateKey, BigInteger publicKey) {
        return publicKey.modPow(privateKey, MODP_2048);
    }

    public static void main(String args[]) {
        boolean allPass = true;
        for (int i = 0; i < 50; i++) {
            SecureRandom rnd = new SecureRandom();
            SecureRandom rnd2 = new SecureRandom();
            DH bob = new DH(512, rnd);
            DH alice = new DH(512, rnd2);

            bob.generateKeyPair();
            alice.generateKeyPair();

            byte bobPublicByte[] = bob.getPublicKey().toByteArray();
            byte bobPublicByte2048[] = paddingTo2048(bobPublicByte);
            byte alicePublicByte[] = alice.getPublicKey().toByteArray();
            byte alicePublicByte2048[] = paddingTo2048(alicePublicByte);


            BigInteger bobPublicKeyDecode = bytesToBigIntegerPositive(bobPublicByte2048);
            BigInteger alicePublicKeyDecode = bytesToBigIntegerPositive(alicePublicByte2048);

            BigInteger bobS = calculateS(bob.getPrivateKey(), alicePublicKeyDecode);
            BigInteger aliceS = calculateS(alice.getPrivateKey(), bobPublicKeyDecode);

            if (bobS.equals(aliceS)) {
                System.out.println(i + ": Pass!");
            } else {
                allPass = false;
                System.out.println(i + ": Fail!");
                break;
            }
        }
        if (allPass) {
            System.out.println("All pass!");
        }
    }

    public static byte[] paddingTo2048(byte[] input) {
        if (input.length == 2048 / 8) {
            return input;
        } else if (input.length < 2048 / 8) {
            byte result[] = new byte[2048 / 8];
            System.arraycopy(input, 0, result, result.length - input.length, input.length);
            return result;
        } else {
            byte result[] = new byte[2048 / 8];
            System.arraycopy(input, input.length - result.length, result, 0, result.length);
            return result;
        }
    }

    public static BigInteger bytesToBigIntegerPositive(byte[] input) {
        if (input[0] < 0) {
            byte p[] = new byte[input.length + 1];
            System.arraycopy(input, 0, p, 1, input.length);
            input = p;
        }
        return new BigInteger(input);
    }

}
