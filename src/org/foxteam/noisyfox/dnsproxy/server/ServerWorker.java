package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.crypto.DH;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class ServerWorker implements Runnable {
    private final Socket mClientSocket;

    public ServerWorker(Socket clientSocket) {
        mClientSocket = clientSocket;
    }

    @Override
    public void run() {
        try {
            doJob();
        } finally {
            try {
                mClientSocket.close(); // 确保连接关闭
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("ServerWorker done!");
    }

    private void doJob() {
        OutputStream outputStream;
        InputStream inputStream;
        try {
            outputStream = mClientSocket.getOutputStream();
            inputStream = mClientSocket.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // 首先，协商加密
        // 创建dh密钥对
        SecureRandom rnd = new SecureRandom();
        DH dh = new DH(256, rnd);
        dh.generateKeyPair();
        // 开始握手
        ServerHandshakeMachine handshakeMachine = new ServerHandshakeMachine(inputStream, outputStream, dh);
        dh = null;// 丢弃
        boolean handshakeSuccess = handshakeMachine.start();

        if (!handshakeSuccess) {
            return;
        }

        System.out.println("ServerWorker handshake success!");
        // 握手完成，开始加密传输

    }
}
