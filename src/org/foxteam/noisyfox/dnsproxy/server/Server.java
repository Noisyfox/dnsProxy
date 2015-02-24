package org.foxteam.noisyfox.dnsproxy.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class Server {

    private int mServerPort = 9473;
    private int mMaxThread = 100;

    private ExecutorService mThreadPool = Executors.newFixedThreadPool(mMaxThread);

    public void loop() {
        ServerSocket listener;
        try {
            listener = new ServerSocket(mServerPort);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        while (true) {
            try {
                Socket socket = listener.accept();
                // TODO:检查该socket对应的地址是不是对应了太多连接
                mThreadPool.execute(new ServerWorker(socket));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
