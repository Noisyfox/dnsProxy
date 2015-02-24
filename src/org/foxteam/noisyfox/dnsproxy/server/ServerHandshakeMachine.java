package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.DH;
import org.foxteam.noisyfox.dnsproxy.cpm.CheckPointMachine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;

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

    private byte mS[] = null;

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
                    e.printStackTrace();
                    failImmediately();
                }
                setCheckPoint(STAT_HELLO);
            case STAT_HELLO:
                try {
                    sendHello();
                } catch (IOException e) {
                    e.printStackTrace();
                    failImmediately();
                }
                setCheckPoint(STAT_TEST);
            case STAT_TEST:
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

        // 分离 client public key
        byte publicKey[] = new byte[2048 / 8];
        System.arraycopy(in, 4, publicKey, 0, publicKey.length);

        BigInteger clientPublicKey = DH.bytesToBigIntegerPositive(publicKey);

        mS = DH.paddingTo2048(DH.calculateS(mDH.getPrivateKey(), clientPublicKey).toByteArray());
    }

    /**
     * say hello with dh public key
     * hello数据的格式：
     * OOH, 01H, 02H, 03H //4字节前缀
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

}
