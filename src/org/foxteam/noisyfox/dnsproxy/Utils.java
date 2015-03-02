package org.foxteam.noisyfox.dnsproxy;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by noisyfox on 15-2-27.
 */
public class Utils {

    public static int readBytesExactly(InputStream inputStreams, byte[] bytes, int offset, int length) throws
            IOException {
        int totalLength = 0;
        while (length > 0) {
            int bRead = inputStreams.read(bytes, offset, length);
            if (bRead == -1) {
                break;
            }

            totalLength += bRead;
            offset += bRead;
            length -= bRead;
        }

        return totalLength;
    }

    public static boolean SHOW_VERBOSE = false;

    public static void showVerbose(String verbose) {
        if (SHOW_VERBOSE) {
            System.out.println(verbose);
        }
    }
}
