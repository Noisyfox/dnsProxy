package org.foxteam.noisyfox.dnsproxy.dns;

import org.foxteam.noisyfox.dnsproxy.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by noisyfox on 15-2-27.
 * 本机发送的，和服务器返回的UDP数据包
 * 包含目标端口，数据长度和数据
 * <p/>
 * 数据包格式：
 * 2字节端口号，2字节数据长度，相应长度的数据
 */
public class UDPDataFrame {

    private final ReentrantLock mIOLock = new ReentrantLock();
    private final byte TMP_BYTE4[] = new byte[4];

    private long mTimeStamp;

    private int mPort;
    private int mDataLength;
    private byte mData[];

    public void setPort(int port) {
        mPort = port;
    }

    public int getPort() {
        return mPort;
    }

    public long getTimeStamp() {
        return mTimeStamp;
    }

    public int getDataLength(){
        return mDataLength;
    }

    public void fillData(byte data[]) {
        fillData(data, 0, data.length);
    }

    public void fillData(byte data[], int offset, int length) {
        mIOLock.lock();
        try {
            if (mData == null || mData.length < length) {
                mData = new byte[length];
            }
            System.arraycopy(data, offset, mData, 0, length);
            Arrays.fill(mData, length, mData.length, (byte) 0x0);

            mDataLength = length;

            mTimeStamp = System.currentTimeMillis();
        } finally {
            mIOLock.unlock();
        }
    }

    public void readData(byte data[]){
        System.arraycopy(mData, 0, data, 0, mDataLength);
    }

    public void writeToStream(OutputStream output) throws IOException {
        mIOLock.lock();
        try {
            TMP_BYTE4[0] = (byte) ((mPort >> 8) & 0xFF);
            TMP_BYTE4[1] = (byte) (mPort & 0xFF);
            TMP_BYTE4[2] = (byte) ((mDataLength >> 8) & 0xFF);
            TMP_BYTE4[3] = (byte) (mDataLength & 0xFF);

            output.write(TMP_BYTE4);
            output.write(mData, 0, mDataLength);
        } finally {
            mIOLock.unlock();
        }
    }

    public int readFromStream(InputStream input) throws IOException {
        mIOLock.lock();
        try {
            int count = Utils.readBytesExactly(input, TMP_BYTE4, 0, 4);
            if (count == 0) {
                return -1;
            }
            if (count != 4) {
                throw new IOException("Unexpected stream end!");
            }

            mPort = 0;
            mPort |= (TMP_BYTE4[0] & 0xFF);
            mPort <<= 8;
            mPort |= (TMP_BYTE4[1] & 0xFF);
            mPort &= 0xFFFF;

            mDataLength = 0;
            mDataLength |= (TMP_BYTE4[2] & 0xFF);
            mDataLength <<= 8;
            mDataLength |= (TMP_BYTE4[3] & 0xFF);
            mDataLength &= 0xFFFF;

            if (mData == null || mData.length < mDataLength) {
                mData = new byte[mDataLength];
            }
            count = Utils.readBytesExactly(input, mData, 0, mDataLength);
            if (count != mDataLength) {
                throw new IOException("Unexpected stream end!");
            }
            Arrays.fill(mData, mDataLength, mData.length, (byte) 0x0);

            mTimeStamp = System.currentTimeMillis();

            return mDataLength;
        } finally {
            mIOLock.unlock();
        }
    }
}
