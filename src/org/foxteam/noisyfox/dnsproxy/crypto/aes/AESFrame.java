package org.foxteam.noisyfox.dnsproxy.crypto.aes;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.foxteam.noisyfox.dnsproxy.crypto.CRC16;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/25.
 * <p/>
 * 一个AES帧
 * 结构为：
 * 2字节的payload加密后长度
 * padding to 128bit的经过aes加密的payload
 * 16bit的crc16校验
 * <p/>
 * 帧中不记录整个帧的长度，只记录加密后payload长度，原始数据长度可由PKCS7Padding在解密过程中自动得到
 * 一个帧中payload采用 AES/CBC/PKCS7Padding 加密
 */
public class AESFrame {

    private static final byte[] INIT_VECTOR = {0x38, 0x37, 0x36, 0x35, 0x34, 0x33, 0x32, 0x31, 0x38,
            0x37, 0x36, 0x35, 0x34, 0x33, 0x32, 0x31}; // CBC初始向量

    private static final byte[] KEY_TEST_128 = {0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38}; // 测试用128bit key

    private static final String ALGORITHM = "AES/CBC/PKCS7Padding";
    private static final IvParameterSpec IV_PARAMETER_SPEC = new IvParameterSpec(INIT_VECTOR);

    public static final int PAYLOAD_MAX_LENGTH = 0xFFFF; // (64K-1)B 最大长度

    static {
        // 加载 bouncycastle
        Security.addProvider(new BouncyCastleProvider());
    }

    public static void main(String args[]) {
        AESFrame sendFrame = new AESFrame(KEY_TEST_128);
        AESFrame receiveFrame = new AESFrame(KEY_TEST_128);

        sendFrame.fillData("啊哈哈哈我是小狐狸！!!!!!!!!!".getBytes());
        ByteArrayInputStream inputStream = new ByteArrayInputStream(sendFrame.getEncryptBytes());

        receiveFrame.readFromStream(inputStream);

        String receiveData = new String(receiveFrame.mPayload);

        System.out.println("aa");
    }

    private ReentrantLock mEncryptLock = new ReentrantLock();
    private final Key mKey;
    private Cipher mCipher = null;

    // TODO:重写数组操作代码，以达到空间复用
    private int mPayloadLength;
    private byte[] mPayload;
    private byte[] mCRC16;

    public AESFrame(byte[] key) {
        mKey = new SecretKeySpec(key, "AES");
        try {
            mCipher = Cipher.getInstance(ALGORITHM, "BC");
        } catch (Exception e) {
            // this won't happen
            e.printStackTrace();
        }
    }

    /**
     * 填充明文数据
     *
     * @param data
     */
    public void fillData(byte[] data) {
        fillData(data, 0, data.length);
    }

    public void fillData(byte[] data, int offset, int length) {
        mEncryptLock.lock();
        try {
            if (length <= 0) {
                throw new IllegalArgumentException();
            } else if (length > PAYLOAD_MAX_LENGTH) {
                throw new IllegalArgumentException();
            }

            byte payload[] = new byte[length];
            System.arraycopy(data, offset, payload, 0, length);

            //计算CRC16
            byte crc16[] = new byte[2];
            CRC16.doCRC(payload, length, crc16);

            mPayload = payload;
            mCRC16 = crc16;
            mPayloadLength = length;
        } finally {
            mEncryptLock.unlock();
        }
    }

    /**
     * 构造并加密帧数据
     */
    public byte[] getEncryptBytes() {
        mEncryptLock.lock();
        try {
            mCipher.init(Cipher.ENCRYPT_MODE, mKey, IV_PARAMETER_SPEC);
            // 加密payload
            byte payloadEnc[] = mCipher.doFinal(mPayload);
            int payloadLength = payloadEnc.length;

            // 构造完整帧数据
            int totalLength = 2 + payloadEnc.length + 2;
            byte frame[] = new byte[totalLength];
            frame[0] = (byte) ((payloadLength >> 8) & 0xff);
            frame[1] = (byte) (payloadLength & 0xff);

            System.arraycopy(payloadEnc, 0, frame, 2, payloadEnc.length);

            frame[totalLength - 2] = mCRC16[0];
            frame[totalLength - 1] = mCRC16[1];

            return frame;
        } catch (InvalidAlgorithmParameterException e) {
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            e.printStackTrace();
        } catch (BadPaddingException e) {
            e.printStackTrace();
        } catch (IllegalBlockSizeException e) {
            e.printStackTrace();
        } finally {
            mEncryptLock.unlock();
        }

        return null;
    }


    private final byte TMP_CRC16[] = new byte[2];

    /**
     * 从一个流中解码1帧数据
     *
     * @param input
     * @return 解码是否成功
     */
    public boolean readFromStream(InputStream input) {
        mEncryptLock.lock();
        try {
            // 先读2字节的长度数据
            byte b2[] = new byte[2];
            try {
                readBytesExactly(input, b2, 0, 2);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            int payloadSize = b2[0];
            payloadSize <<= 8;
            payloadSize |= b2[1];
            payloadSize &= 0xFFFF;
            if (payloadSize <= 0) {
                return false;
            }

            // 读取payload
            byte payload[] = new byte[payloadSize];
            try {
                readBytesExactly(input, payload, 0, payloadSize);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            // 读取crc16
            try {
                readBytesExactly(input, b2, 0, 2);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            // 解密数据
            mCipher.init(Cipher.DECRYPT_MODE, mKey, IV_PARAMETER_SPEC);
            payload = mCipher.doFinal(payload);
            payloadSize = payload.length;

            // 计算CRC16
            CRC16.doCRC(payload, payloadSize, TMP_CRC16);
            // 验证CRC16
            if (TMP_CRC16[0] != b2[0] || TMP_CRC16[1] != b2[1]) {
                return false;
            }

            byte payload_dec[] = new byte[payloadSize];
            System.arraycopy(payload, 0, payload_dec, 0, payloadSize);

            mPayloadLength = payloadSize;
            mPayload = payload_dec;
            mCRC16 = Arrays.copyOf(TMP_CRC16, 2);

            return true;

        } catch (InvalidAlgorithmParameterException e) {
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            e.printStackTrace();
        } catch (BadPaddingException e) {
            e.printStackTrace();
        } catch (IllegalBlockSizeException e) {
            e.printStackTrace();
        } finally {
            mEncryptLock.unlock();
        }
        return false;
    }

    private static void readBytesExactly(InputStream inputStreams, byte[] bytes, int offset, int length) throws IOException {
        while (length > 0) {
            int bRead = inputStreams.read(bytes, offset, length);
            if (bRead == -1) {
                throw new IOException("Unexpected stream end!");
            }

            offset += bRead;
            length -= bRead;
        }
    }
}
