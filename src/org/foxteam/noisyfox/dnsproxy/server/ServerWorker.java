package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.Utils;
import org.foxteam.noisyfox.dnsproxy.crypto.DH;
import org.foxteam.noisyfox.dnsproxy.dns.UDPDataFrame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class ServerWorker implements Runnable {
    private final SocketChannel mClientChannel;
    private final Socket mClientSocket;
    private final InetAddress mDNSAddress;

    public ServerWorker(SocketChannel clientChannel, InetAddress dnsAddress) {
        mDNSAddress = dnsAddress;
        mClientChannel = clientChannel;
        mClientSocket = clientChannel.socket();
    }

    @Override
    public void run() {
        try {
            doJob();
        } finally {
            try {
                mClientChannel.close(); // 确保连接关闭
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Utils.showVerbose("ServerWorker done!");
    }

    private final ReentrantLock mTreadLock = new ReentrantLock();
    private final Condition mThreadCondition = mTreadLock.newCondition();

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

        Utils.showVerbose("ServerWorker handshake success!");
        // 握手完成，开始加密传输

        outputStream = handshakeMachine.getEncryptedOutputStream();
        inputStream = handshakeMachine.getEncryptedInputStream();

        RespondFlinger flinger = null;
        Thread requestThread = null;
        Thread respondThread = null;

        mTreadLock.lock();
        try {
            flinger = new RespondFlinger(mDNSAddress);
            flinger.start();

            // 启动请求和响应线程，本线程成为监控线程，如果请求或响应线程出错，
            // 则负责结束整个ServerWorker
            requestThread = new RequestThread(flinger, inputStream);
            respondThread = new RespondThread(flinger, outputStream);

            requestThread.start();
            respondThread.start();

            try {
                mThreadCondition.await();
            } catch (InterruptedException ignored) {
            }
        } finally {
            mTreadLock.unlock();

            if (flinger != null) {
                flinger.stop();
            }

            if (requestThread != null) {
                requestThread.interrupt();
            }
            if (respondThread != null) {
                respondThread.interrupt();
            }
            try {
                if (requestThread != null) {
                    requestThread.join();
                }
            } catch (InterruptedException ignored) {
            }
            try {
                if (respondThread != null) {
                    respondThread.join();
                }
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * 接收从客户端发来的请求并提交给flinger去处理
     */
    private class RequestThread extends Thread {
        private final RespondFlinger mFlinger;
        private final InputStream mIn;

        public RequestThread(RespondFlinger respondFlinger, InputStream in) {
            mFlinger = respondFlinger;
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

                Utils.showVerbose("Client request! Port:" + frame.getPort());

                mFlinger.queueRequestAndNotify(frame);
            }
        }
    }

    /**
     * 接收flinger发来的请求结果并返回给客户端
     */
    private class RespondThread extends Thread {
        private final RespondFlinger mFlinger;
        private final OutputStream mOut;

        public RespondThread(RespondFlinger respondFlinger, OutputStream out) {
            mFlinger = respondFlinger;
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
                    mFlinger.pollRespondOrWait(frame);
                } catch (InterruptedException e) {
                    return;
                }

                try {
                    Utils.showVerbose("Respond send! Length:" + frame.getDataLength() + " port:" + frame.getPort());
                    frame.writeToStream(mOut);
                    mOut.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
            }
        }
    }
}
