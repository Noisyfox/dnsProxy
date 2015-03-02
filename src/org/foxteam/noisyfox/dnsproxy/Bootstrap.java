package org.foxteam.noisyfox.dnsproxy;

import org.foxteam.noisyfox.dnsproxy.client.Client;
import org.foxteam.noisyfox.dnsproxy.server.Server;

/**
 * Created by Noisyfox on 2015/3/2.
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


        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("-v".equals(arg) || "--vv".equals(arg)) {
                Utils.SHOW_VERBOSE = true;
            }
        }

        String mode = args[0];
        if ("client".equals(mode)) {
            Client client = new Client();
            if (!client.parseArgs(args)) {
                printUsage();
                System.exit(-1);
            }
            client.startProxy();
        } else if ("server".equals(mode)) {
            Server server = new Server();
            if (!server.parseArgs(args)) {
                printUsage();
                System.exit(-2);
            }
            server.loop();
        } else {
            printUsage();
        }
    }
}
