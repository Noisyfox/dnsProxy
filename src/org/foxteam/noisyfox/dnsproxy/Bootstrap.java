package org.foxteam.noisyfox.dnsproxy;

import org.foxteam.noisyfox.dnsproxy.client.Client;
import org.foxteam.noisyfox.dnsproxy.server.Server;

/**
 * Created by Noisyfox on 2015/3/2.
 * <p/>
 * interface implements apache jsvc methods
 * see http://commons.apache.org/proper/commons-daemon/jsvc.html
 */
public class Bootstrap {

    private static final String USAGE = "usage:\n" +
            "dnsProxy client [-p SERVER_PORT] -s SERVER_ADDRESS [-v]\n" +
            "dnsProxy server [-p SERVER_PORT] [-d DNS_PROVIDER] [-v]\n" +
            "options:\n" +
            "-p,--port SERVER_PORT          server port, default is 7373\n" +
            "-s,--server SERVER_ADDRESS     server address\n" +
            "-d,--dns DNS_PROVIDER          DNS provider, default is 8.8.8.8\n" +
            "-v,-vv                         verbose mode";

    private static void printInfo() {

    }

    private static void printUsage() {
        System.out.println(USAGE);
    }

    public static void main(String args[]) {
        printInfo();

        if (args.length == 0) {
            printUsage();
            return;
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.init(args);

        bootstrap.start();
        bootstrap.waitToStop();
        bootstrap.destroy();
    }

    private Application mApplication;

    public void init(String[] args) {
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("-v".equals(arg) || "--vv".equals(arg)) {
                Utils.SHOW_VERBOSE = true;
            }
        }

        Application application;
        String mode = args[0];
        if ("client".equals(mode)) {
            application = new Client();
        } else if ("server".equals(mode)) {
            application = new Server();
        } else {
            printUsage();
            return;
        }

        if (!application.init(args)) {
            printUsage();
            System.exit(-1);
        }

        mApplication = application;
    }

    public void start() {
        if (mApplication != null) {
            mApplication.start();
        }
    }

    public void stop() {
        if (mApplication != null) {
            mApplication.stop();
        }
    }

    public void destroy() {
        if (mApplication != null) {
            mApplication.destroy();
        }
    }

    private void waitToStop() {
        if (mApplication != null) {
            mApplication.waitToStop();
        }
    }
}
