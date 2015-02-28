package org.foxteam.noisyfox.dnsproxy.client;

import org.foxteam.noisyfox.dnsproxy.crypto.DH;
import org.foxteam.noisyfox.dnsproxy.dns.UDPDataFrame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class ClientWorker extends Thread {

    private final Socket mServerSocket;
    private final RequestFlinger mRequestFlinger;

    public ClientWorker(Socket serverSocket, RequestFlinger requestFlinger) {
        mServerSocket = serverSocket;
        mRequestFlinger = requestFlinger;
    }

    @Override
    public void run() {
        try {
            doJob();
        } finally {
            try {
                mServerSocket.close(); // 确保连接关闭
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("ClientWorker done!");
    }

    private void doJob() {
        OutputStream outputStream;
        InputStream inputStream;
        try {
            outputStream = mServerSocket.getOutputStream();
            inputStream = mServerSocket.getInputStream();
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
        ClientHandshakeMachine handshakeMachine = new ClientHandshakeMachine(inputStream, outputStream, dh);
        dh = null;// 丢弃
        boolean handshakeSuccess = handshakeMachine.start();

        if (!handshakeSuccess) {
            return;
        }

        System.out.println("ClientWorker handshake success!");
        // 握手完成，开始加密传输

        outputStream = handshakeMachine.getEncryptedOutputStream();
        inputStream = handshakeMachine.getEncrpytedInputStream();

        // 启动请求和响应线程，本线程成为监控线程，如果请求或响应线程出错，
        // 则负责结束整个ClientWorker，此时主线程会重新启动新的Worker
        Thread requestThread = new RequestThread(outputStream);
        Thread respondThread = new RespondThread(inputStream);
        mTreadLock.lock();
        try {
            requestThread.start();
            respondThread.start();

            try {
                mThreadCondition.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } finally {
            requestThread.interrupt();
            respondThread.interrupt();
            mTreadLock.unlock();
            try {
                requestThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            try {
                respondThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private final ReentrantLock mTreadLock = new ReentrantLock();
    private final Condition mThreadCondition = mTreadLock.newCondition();

    /**
     * 负责从RequestFlinger获取请求并发送至服务器
     */
    private class RequestThread extends Thread {
        private final OutputStream mOut;

        public RequestThread(OutputStream out) {
            mOut = out;
        }

        @Override
        public void run() {
            try {
                doJob();
            } finally {
                // 通知监控进程退出
                mTreadLock.lock();
                try {
                    mThreadCondition.signalAll();
                } finally {
                    mTreadLock.unlock();
                }
            }
        }

        private void doJob() {
            UDPDataFrame frame = new UDPDataFrame();
            while (!interrupted()) {
                try {
                    mRequestFlinger.pollRequestOrWait(frame);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return;
                }

                try {
                    System.out.println("Request send! Port:" + frame.getPort());
                    frame.writeToStream(mOut);
                    mOut.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
        }
    }

    /**
     * 负责从服务器获取Respond并提交给RequestFlinger
     */
    private class RespondThread extends Thread {
        private final InputStream mIn;

        public RespondThread(InputStream in) {
            mIn = in;
        }

        @Override
        public void run() {
            try {
                doJob();
            } finally {
                // 通知监控进程退出
                mTreadLock.lock();
                try {
                    mThreadCondition.signalAll();
                } finally {
                    mTreadLock.unlock();
                }
            }
        }

        private void doJob() {
            UDPDataFrame frame = new UDPDataFrame();
            while (!interrupted()) {
                try {
                    int count = frame.readFromStream(mIn);
                    if (count == -1) {
                        return;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }

                if (interrupted()) {
                    return;
                }

                System.out.println("Server respond! Length:" + frame.getDataLength() + " port:" + frame.getPort());

                mRequestFlinger.queueRespondAndNotify(frame);
            }
        }
    }
}
