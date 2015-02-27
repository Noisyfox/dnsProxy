package org.foxteam.noisyfox.dnsproxy.crypto;

/**
 * Created by Noisyfox on 2015/2/25.
 * 一个CRC16实现
 */
public class CRC16 {

    public static byte[] doCRC(byte[] data, int offset, int dataLen, byte[] pCRC, int pCRCOffset) {
        int CRC = 0x0000ffff;
        int POLYNOMIAL = 0x0000a001;
        int i, j;

        if (dataLen == 0) {
            return pCRC;
        }
        int endIndex = offset + dataLen;
        for (i = offset; i < endIndex; i++) {
            CRC ^= ((int) data[i] & 0x000000ff);
            for (j = 0; j < 8; j++) {
                if ((CRC & 0x00000001) != 0) {
                    CRC >>= 1;
                    CRC ^= POLYNOMIAL;
                } else {
                    CRC >>= 1;
                }
            }
        }

        pCRC[pCRCOffset] = (byte) (CRC & 0xff);
        pCRC[pCRCOffset + 1] = (byte) ((CRC >> 8) & 0xff);

        return pCRC;
    }

    public static byte[] doCRC(byte[] data, int dataLen, byte[] pCRC) {
        return doCRC(data, 0, dataLen, pCRC, 0);
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        byte[] aa = {0x30, 0x30, 0x34, 0x36, 0x46, 0x44, 0x36, 0x30, 0x30, 0x30, 0x01, 0x72, 0x65,
                0x66, 0x65, 0x72, 0x69, 0x6E, 0x66, 0x6F, 0x2E, 0x63, 0x73, 0x76, 0x00, 0x00
                , 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                , 0x00, 0x00, 0x00, 0x01, (byte) 0xf4, 0x01};
        byte[] bb = new byte[3];
        doCRC(aa, aa.length, bb);

        System.out.println(Integer.toHexString((int) bb[0] & 0x000000ff));
        System.out.println(Integer.toHexString((int) bb[1] & 0x000000ff));

    }

}
