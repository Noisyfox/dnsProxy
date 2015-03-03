package org.foxteam.noisyfox.dnsproxy;

import org.foxteam.noisyfox.dnsproxy.client.Client;
import org.foxteam.noisyfox.dnsproxy.server.Server;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Created by Noisyfox on 2015/3/2.
 * <p/>
 * interface implements apache jsvc methods
 * see http://commons.apache.org/proper/commons-daemon/jsvc.html
 */
public class Bootstrap {

    private static final String USAGE = "usage:\n" +
            "dnsProxy client [-c CONFIG_FILE] [-p SERVER_PORT] [-s SERVER_ADDRESS] [-v]\n" +
            "dnsProxy server [-c CONFIG_FILE] [-p SERVER_PORT] [-d DNS_PROVIDER] [-v]\n" +
            "options:\n" +
            "-c,--config CONFIG_FILE        proxy config\n" +
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

        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.init(args);

        bootstrap.start();
        bootstrap.waitToStop();
        bootstrap.destroy();
    }

    private Application mApplication;
    private JSONObject mConfig = null;

    public void init(String[] args) {
        Utils.SHOW_VERBOSE = false;

        if (!preInit(args)) {
            printUsage();
            return;
        }
        if (!readConfig(mConfig)) {
            printUsage();
            return;
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

        if (!application.init(args, mConfig)) {
            printUsage();
            System.exit(-1);
        }

        mApplication = application;
    }

    private boolean readConfig(JSONObject cfg) {
        if (cfg == null) {
            return true;
        }

        String v = (String) cfg.get("verbose");
        if (v != null) {
            Utils.SHOW_VERBOSE = "true".equals(v);
        }

        return true;
    }

    private boolean preInit(String[] args) {
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("-v".equals(arg) || "--vv".equals(arg)) {
                Utils.SHOW_VERBOSE = true;
            } else if ("-c".equals(arg) || "--config".equals(arg)) {
                if (i == args.length - 1) {
                    System.out.println("option " + arg + " requires argument");
                    return false;
                }
                String c = args[++i];
                // 解析配置文件
                Reader cr = null;
                try {
                    cr = new FileReader(c);
                    JSONParser parser = new JSONParser();
                    Object cfgObj = parser.parse(cr);
                    if (cfgObj instanceof JSONObject) {
                        mConfig = (JSONObject) cfgObj;
                    } else {
                        System.out.println("Wrong config format!");
                        return false;
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    return false;
                } catch (ParseException e) {
                    e.printStackTrace();
                    return false;
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                } finally {
                    if (cr != null) {
                        try {
                            cr.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        return true;
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
