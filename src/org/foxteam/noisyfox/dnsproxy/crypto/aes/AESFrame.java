package org.foxteam.noisyfox.dnsproxy.crypto.aes;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.foxteam.noisyfox.dnsproxy.Utils;
import org.foxteam.noisyfox.dnsproxy.crypto.CRC16;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
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

        try {
            receiveFrame.readFromStream(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }

        byte receiveBytes[] = new byte[receiveFrame.getPayloadSize()];
        receiveFrame.getPayloadData(receiveBytes);
        String receiveData = new String(receiveBytes);

        System.out.println("aa");
    }

    private ReentrantLock mEncryptLock = new ReentrantLock();
    private final Key mKey;
    private Cipher mCipher = null;

    private int mPayloadLength;
    private byte[] mPayload;

    public AESFrame(byte[] key) {
        mKey = new SecretKeySpec(key, "AES");
        try {
            mCipher = Cipher.getInstance(ALGORITHM, "BC");
        } catch (Exception e) {
            // this won't happen
            e.printStackTrace();
        }
    }

    public int getPayloadSize() {
        return mPayloadLength;
    }

    public int getPayloadData(byte dataOut[]) {
        mEncryptLock.lock();
        try {
            System.arraycopy(mPayload, 0, dataOut, 0, mPayloadLength);

            return mPayloadLength;
        } finally {
            mEncryptLock.unlock();
        }
    }

    /**
     * 填充明文数据
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

            // 复用以前的空间
            if (mPayload == null || mPayload.length < length || mPayload.length > length * 2) {
                mPayload = new byte[length];
            }
            System.arraycopy(data, offset, mPayload, 0, length);
            Arrays.fill(mPayload, length, mPayload.length, (byte) 0);

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
            byte payloadEnc[] = mCipher.doFinal(mPayload, 0, mPayloadLength);
            int payloadLength = payloadEnc.length;

            // 构造完整帧数据
            int totalLength = 2 + payloadLength + 2;
            byte frame[] = new byte[totalLength];
            frame[0] = (byte) ((payloadLength >> 8) & 0xff);
            frame[1] = (byte) (payloadLength & 0xff);

            System.arraycopy(payloadEnc, 0, frame, 2, payloadLength);

            // 计算CRC16
            CRC16.doCRC(payloadEnc, payloadLength, TMP_CRC16);

            frame[totalLength - 2] = TMP_CRC16[0];
            frame[totalLength - 1] = TMP_CRC16[1];

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
    private final byte TMP_READ[] = new byte[2];

    /**
     * 从一个流中解码1帧数据
     *
     * @return 解码后的数据长度，-1代表流结束
     */
    public int readFromStream(InputStream input) throws IOException {
        mEncryptLock.lock();
        try {
            // 先读2字节的长度数据
            int bRead = Utils.readBytesExactly(input, TMP_READ, 0, 2);
            if (bRead == 0) { // 流结束
                return -1;
            }
            if (bRead != 2) {
                throw new IOException("Unexpected stream end!");
            }

            int payloadSize = TMP_READ[0];
            payloadSize <<= 8;
            payloadSize |= TMP_READ[1];
            payloadSize &= 0xFFFF;
            if (payloadSize <= 0) {
                throw new IOException("Unexpected payload size " + payloadSize);
            }

            // 计算最大长度
            if (mPayload == null || mPayload.length < payloadSize || mPayload.length > payloadSize * 2) {
                mPayload = new byte[payloadSize];
            }

            // 读取payload
            bRead = Utils.readBytesExactly(input, mPayload, 0, payloadSize);
            if (bRead != payloadSize) {
                throw new IOException("Unexpected stream end!");
            }

            // 读取crc16
            bRead = Utils.readBytesExactly(input, TMP_READ, 0, 2);
            if (bRead != 2) {
                throw new IOException("Unexpected stream end!");
            }

            // 计算CRC16
            CRC16.doCRC(mPayload, payloadSize, TMP_CRC16);
            // 验证CRC16
            if (TMP_CRC16[0] != TMP_READ[0] || TMP_CRC16[1] != TMP_READ[1]) {
                throw new IOException("CRC mismatch");
            }

            // 解密数据
            mCipher.init(Cipher.DECRYPT_MODE, mKey, IV_PARAMETER_SPEC);
            byte payload_dec[] = mCipher.doFinal(mPayload, 0, payloadSize);

            payloadSize = payload_dec.length;
            System.arraycopy(payload_dec, 0, mPayload, 0, payloadSize);
            Arrays.fill(mPayload, payloadSize, mPayload.length, (byte) 0);

            mPayloadLength = payloadSize;

            return mPayloadLength;
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
        throw new IOException("Unknown error");
    }
}
