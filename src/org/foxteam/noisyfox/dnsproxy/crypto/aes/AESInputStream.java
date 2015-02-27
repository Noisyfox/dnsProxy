package org.foxteam.noisyfox.dnsproxy.crypto.aes;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/25.
 */
public class AESInputStream extends InputStream {

    private final ReentrantLock mIOLock = new ReentrantLock();
    private final InputStream mInput;
    private final AESFrame mFrame;

    private int mBufferIndex = 0;
    private int mBufferContentLength = 0;
    private byte mBuffer[] = null;

    public AESInputStream(InputStream in, byte key[], byte iv[]) {
        mInput = in;
        mFrame = new AESFrame(key, iv);
    }

    @Override
    public int read() throws IOException {
        mIOLock.lock();
        try {
            // 确认目前的 Buffer 还有剩余
            if (mBuffer == null || mBufferIndex >= mBufferContentLength) {
                int newContentLength = mFrame.readFromStream(mInput);
                if (newContentLength == -1) {
                    return -1;
                }
                if (mBuffer == null || mBuffer.length < newContentLength) {
                    mBuffer = new byte[newContentLength];
                }
                mFrame.getPayloadData(mBuffer);
                mBufferContentLength = newContentLength;
                mBufferIndex = 0;
            }

            return mBuffer[mBufferIndex++];
        } finally {
            mIOLock.unlock();
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        mIOLock.lock();
        try {
            boolean hasByteRead = false;
            int readCount = 0;

            while (len > 0) {
                int byteAvailable; // buffer还剩多少byte可以读取
                if (mBuffer == null) {
                    byteAvailable = 0;
                } else {
                    byteAvailable = mBufferContentLength - mBufferIndex;
                }

                if (byteAvailable <= 0) { // buffer空或者读完
                    // read more
                    int newContentLength = mFrame.readFromStream(mInput);
                    if (newContentLength == -1) {
                        if (hasByteRead) {
                            return readCount;
                        } else {
                            return -1;
                        }
                    }

                    if (mBuffer == null || mBuffer.length < newContentLength) {
                        mBuffer = new byte[newContentLength];
                    }
                    mFrame.getPayloadData(mBuffer);
                    mBufferContentLength = newContentLength;
                    mBufferIndex = 0;
                } else {
                    int byteNeedReadCount = Math.min(byteAvailable, len);
                    System.arraycopy(mBuffer, mBufferIndex, b, off, byteNeedReadCount);
                    off += byteNeedReadCount;
                    mBufferIndex += byteNeedReadCount;
                    readCount += byteNeedReadCount;
                    len -= byteNeedReadCount;
                    hasByteRead = true;
                }
            }

            return readCount;
        } finally {
            mIOLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        mInput.close();
    }

}
