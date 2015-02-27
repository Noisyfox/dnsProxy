package org.foxteam.noisyfox.dnsproxy.client;

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
public class ClientHandshakeMachine extends CheckPointMachine {

    private static final int STAT_INIT = 0; // 初始状态，发送hello
    private static final int STAT_SERVER_HELLO = 1; // 等待并解析服务器hello
    private static final int STAT_TEST = 2; // 进行加密测试，验证密钥正确性

    private final DH mDH;
    private final InputStream mInput;
    private final OutputStream mOutput;

    private AESInputStream mAESIn;
    private AESOutputStream mAESOut;

    public ClientHandshakeMachine(InputStream inputStream, OutputStream outputStream, DH dh) {
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
                    sendHello();
                } catch (IOException e) {
                    e.printStackTrace();
                    failImmediately();
                }
                setCheckPoint(STAT_SERVER_HELLO);
            case STAT_SERVER_HELLO:
                try {
                    readServerHello();
                } catch (IOException e) {
                    e.printStackTrace();
                    failImmediately();
                }
                setCheckPoint(STAT_TEST);
            case STAT_TEST:
                try {
                    test();
                } catch (IOException e) {
                    e.printStackTrace();
                    failImmediately();
                }
                finish();
        }
    }

    private static final int DATA_SIZE_HELLO = 2048 / 8 + 4;
    private static final int DATA_SIZE_TEST = 1024 / 8 + 4;

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
     * 读取服务器的 hello 数据并算出 S
     */
    private void readServerHello() throws IOException {
        byte in[] = new byte[DATA_SIZE_HELLO];
        int byteRead = 0;
        while (byteRead < DATA_SIZE_HELLO) { // 读取服务器hello数据
            int remain = DATA_SIZE_HELLO - byteRead;
            int thisRead = mInput.read(in, byteRead, remain);
            if (thisRead == -1) {
                failImmediately();
            }
            byteRead += thisRead;
        }

        // 验证前4个字节
        for (byte i = 0; i < 4; i++) {
            if (in[i] != i) {
                failImmediately();
            }
        }

        // 分离 server public key
        byte publicKey[] = new byte[2048 / 8];
        System.arraycopy(in, 4, publicKey, 0, publicKey.length);

        BigInteger serverPublicKey = DH.bytesToBigIntegerPositive(publicKey);

        byte ikm[] = DH.paddingTo2048(DH.calculateS(mDH.getPrivateKey(), serverPublicKey).toByteArray());
        byte S[] = HKDF.doHKDF(ikm, 128 / 8); // 导出密钥

        mAESIn = new AESInputStream(mInput, S);
        mAESOut = new AESOutputStream(mOutput, S);
    }

    /**
     * 验证密钥
     * 产生一段随机的字符串，将其发送给服务器，
     * 服务器解密后计算字符串的hash，并加密发送回客户端
     * 客户端解密后通过比对hash，以确认密钥是否正确
     * 同时对服务器发来的串做hash，返回给服务器校验
     * <p/>
     * 校验数据格式：
     * 01H, 00H, 01H, 00H //4字节前缀
     * xxxxxxxxxxxxxxxxxx //8字节随机串
     * <p/>
     * 返回数据格式:
     * 00H, 01H, 00H, 01H //4字节前缀
     * xxH, xxH           //2字节CRC16
     * xxxxxxxxxxxxxxxxxx //8字节服务器随机串
     * <p/>
     * 连接建立串格式：
     * 00H, 00H, 01H, 01H //4字节前缀
     * xxH, xxH           //2字节服务器CRC16
     */
    private void test() throws IOException {
        Random r = new Random();
        byte test[] = new byte[16];
        r.nextBytes(test);
        test[0] = 0x01;
        test[1] = 0x00;
        test[2] = 0x01;
        test[3] = 0x00;

        mAESOut.write(test, 0, 12);
        mAESOut.flush();

        CRC16.doCRC(test, 4, 8, test, 14);

        int count = Utils.readBytesExactly(mAESIn, test, 0, 14);
        if (count != 14) {
            throw new IOException("Unexpected stream end!");
        }

        // 校验
        if (test[0] != 0x00 || test[1] != 0x01
                || test[2] != 0x00 || test[3] != 0x01) {
            throw new IOException("Encrypt establish failed!");
        }

        if (test[4] != test[14] || test[5] != test[15]) {
            throw new IOException("Encrypt establish failed! CRC fail");
        }

        // 生成服务器校验信息

        CRC16.doCRC(test, 6, 8, test, 4);
        test[0] = 0x00;
        test[1] = 0x00;
        test[2] = 0x01;
        test[3] = 0x01;
        mAESOut.write(test, 0, 6);
        mAESOut.flush();

        // 从这里开始，连接正式建立
    }
}
