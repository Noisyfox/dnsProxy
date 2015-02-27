package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.Utils;
import org.foxteam.noisyfox.dnsproxy.crypto.CRC16;
import org.foxteam.noisyfox.dnsproxy.crypto.DH;
import org.foxteam.noisyfox.dnsproxy.cpm.CheckPointMachine;
import org.foxteam.noisyfox.dnsproxy.crypto.HKDF;
import org.foxteam.noisyfox.dnsproxy.crypto.aes.AESInputStream;
import org.foxteam.noisyfox.dnsproxy.crypto.aes.AESOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Random;

/**
 * Created by Noisyfox on 2015/2/25.
 * 加密握手阶段状态机
 */
public class ServerHandshakeMachine extends CheckPointMachine {
    private static final int STAT_INIT = 0; // 初始状态，等待客户端hello
    private static final int STAT_HELLO = 1; // 发送服务器hello
    private static final int STAT_TEST = 2; // 进行加密测试，验证密钥正确性

    private final DH mDH;
    private final InputStream mInput;
    private final OutputStream mOutput;

    private AESInputStream mAESIn;
    private AESOutputStream mAESOut;

    public ServerHandshakeMachine(InputStream inputStream, OutputStream outputStream, DH dh) {
        super(STAT_INIT);

        mDH = dh;
        mInput = inputStream;
        mOutput = outputStream;
    }

    @Override
    protected void run(int checkPoint) {
        switch (checkPoint) {
            case STAT_INIT:
                try {
                    waitClientHello();
                } catch (IOException e) {
                    failImmediately(e);
                }
                setCheckPoint(STAT_HELLO);
            case STAT_HELLO:
                try {
                    sendHello();
                } catch (IOException e) {
                    failImmediately(e);
                }
                setCheckPoint(STAT_TEST);
            case STAT_TEST:
                try {
                    test();
                } catch (IOException e) {
                    failImmediately(e);
                }
                finish();
        }
    }


    private static final int DATA_SIZE_HELLO = 2048 / 8 + 4;

    /**
     * 接受客户端的 hello 数据并算出 S
     */
    private void waitClientHello() throws IOException {
        byte in[] = new byte[DATA_SIZE_HELLO];
        int byteRead = 0;
        while (byteRead < DATA_SIZE_HELLO) { // 读取服务器hello数据
            int remain = DATA_SIZE_HELLO - byteRead;
            int thisRead = mInput.read(in, byteRead, remain);
            if (thisRead == -1) {
                throw new IOException("Unexpected end of stream");
            }
            byteRead += thisRead;
        }

        // 验证前4个字节
        for (byte i = 0; i < 4; i++) {
            if (in[i] != i) {
                throw new IOException("Unknown hello package format");
            }
        }

        // 分离 client public key
        byte publicKey[] = new byte[2048 / 8];
        System.arraycopy(in, 4, publicKey, 0, publicKey.length);

        BigInteger clientPublicKey = DH.bytesToBigIntegerPositive(publicKey);

        byte ikm[] = DH.paddingTo2048(DH.calculateS(mDH.getPrivateKey(), clientPublicKey).toByteArray());
        byte S[] = HKDF.doHKDF(ikm, 128 / 8); // 导出密钥

        mAESIn = new AESInputStream(mInput, S);
        mAESOut = new AESOutputStream(mOutput, S);
    }

    /**
     * say hello with dh public key
     * hello数据的格式：
     * 00H, 01H, 02H, 03H //4字节前缀
     * xxxxxxxxxxxxxxxxxx //2048位 public key
     */
    private void sendHello() throws IOException {
        byte out[] = new byte[DATA_SIZE_HELLO];
        out[0] = 0x00;
        out[1] = 0x01;
        out[2] = 0x02;
        out[3] = 0x03;
        byte publicKey[] = DH.paddingTo2048(mDH.getPublicKey().toByteArray());
        System.arraycopy(publicKey, 0, out, 4, publicKey.length);

        mOutput.write(out);
        mOutput.flush();
    }

    /**
     * 校验客户端发来的测试数据
     * 同时向客户端发送校验数据
     */
    private void test() throws IOException {
        byte test[] = new byte[14];
        byte crc[] = new byte[2];

        int count = Utils.readBytesExactly(mAESIn, test, 0, 12);
        if (count != 12) {
            throw new IOException("Unexpected stream end!");
        }

        if (test[0] != 0x01 || test[1] != 0x00 || test[2] != 0x01 || test[3] != 0x00) {
            throw new IOException("Encrypt establish failed!");
        }

        CRC16.doCRC(test, 4, 8, crc, 0);
        Random r = new Random();
        r.nextBytes(test);

        test[0] = 0x00;
        test[1] = 0x01;
        test[2] = 0x00;
        test[3] = 0x01;
        test[4] = crc[0];
        test[5] = crc[1];

        mAESOut.write(test, 0, 14);
        mAESOut.flush();

        CRC16.doCRC(test, 6, 8, crc, 0);

        count = Utils.readBytesExactly(mAESIn, test, 0, 6);
        if (count != 6) {
            throw new IOException("Unexpected stream end!");
        }

        if (test[0] != 0x00 || test[1] != 0x00 || test[2] != 0x01 || test[3] != 0x01) {
            throw new IOException("Encrypt establish failed!");
        }

        if (test[4] != crc[0] || test[5] != crc[1]) {
            throw new IOException("Encrypt establish failed! CRC fail");
        }

        // 从这里开始，连接正式建立
    }

}
