package com.dawn.tcp;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * TCP 文件传输辅助类，处理文件的 Base64 编码/解码和分块传输协议。
 * 所有文件传输对业务层透明，不会触发 onReceiveData 回调。
 *
 * <p>传输协议：</p>
 * <ul>
 *   <li>{@code ##FILE_START##fileName##fileSize} - 文件传输开始</li>
 *   <li>{@code ##FILE_DATA##base64Chunk} - 文件数据块</li>
 *   <li>{@code ##FILE_END##md5Hash} - 文件传输结束（含 MD5 校验）</li>
 * </ul>
 */
class TcpFileHelper {

    private static final String TAG = "TcpFileHelper";

    /** 文件传输开始标识 */
    static final String FILE_START = "##FILE_START##";
    /** 文件数据块标识 */
    static final String FILE_DATA = "##FILE_DATA##";
    /** 文件传输结束标识 */
    static final String FILE_END = "##FILE_END##";

    /** 每次读取原始文件数据的大小（字节），Base64 后约 43KB，在 64KB 行限制内 */
    static final int CHUNK_SIZE = 32 * 1024;

    private TcpFileHelper() {
    }

    /**
     * 判断一行消息是否为文件传输协议消息
     */
    static boolean isFileProtocol(String line) {
        return line.startsWith(FILE_START) || line.startsWith(FILE_DATA) || line.startsWith(FILE_END);
    }

    /**
     * 计算文件的 MD5 值
     *
     * @param file 文件
     * @return MD5 十六进制字符串，失败返回空字符串
     */
    static String md5(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            fis.close();
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.e(TAG, "MD5 calculation failed", e);
            return "";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // ==================== 文件接收状态机 ====================

    /**
     * 文件接收上下文，每个连接维护一个实例
     */
    static class FileReceiveContext {
        String fileName;
        long fileSize;
        long receivedSize;
        File tempFile;
        FileOutputStream outputStream;
        MessageDigest digest;

        /**
         * 开始接收文件
         *
         * @param saveDir  保存目录
         * @param fileName 文件名
         * @param fileSize 文件总大小
         * @return 是否初始化成功
         */
        boolean begin(File saveDir, String fileName, long fileSize) {
            try {
                reset();
                if (!saveDir.exists() && !saveDir.mkdirs()) {
                    Log.e(TAG, "Failed to create save directory: " + saveDir);
                    return false;
                }
                this.fileName = fileName;
                this.fileSize = fileSize;
                this.receivedSize = 0;
                this.tempFile = new File(saveDir, fileName + ".tmp");
                this.outputStream = new FileOutputStream(tempFile);
                this.digest = MessageDigest.getInstance("MD5");
                return true;
            } catch (IOException | NoSuchAlgorithmException e) {
                Log.e(TAG, "Begin file receive failed", e);
                reset();
                return false;
            }
        }

        /**
         * 写入一个数据块
         *
         * @param base64Data Base64 编码的数据块
         * @return 当前接收进度（0-100），失败返回 -1
         */
        int writeChunk(String base64Data) {
            if (outputStream == null) {
                return -1;
            }
            try {
                byte[] data = Base64.decode(base64Data, Base64.NO_WRAP);
                outputStream.write(data);
                digest.update(data);
                receivedSize += data.length;
                if (fileSize > 0) {
                    return (int) (receivedSize * 100 / fileSize);
                }
                return 0;
            } catch (Exception e) {
                Log.e(TAG, "Write chunk failed", e);
                return -1;
            }
        }

        /**
         * 完成文件接收，校验 MD5
         *
         * @param expectedMd5 期望的 MD5 值
         * @return 最终文件路径，校验失败或出错返回 null
         */
        String finish(String expectedMd5) {
            if (outputStream == null || tempFile == null) {
                reset();
                return null;
            }
            try {
                outputStream.flush();
                outputStream.close();
                outputStream = null;

                String actualMd5 = bytesToHex(digest.digest());
                if (!actualMd5.equalsIgnoreCase(expectedMd5)) {
                    Log.e(TAG, "MD5 mismatch: expected=" + expectedMd5 + ", actual=" + actualMd5);
                    tempFile.delete();
                    reset();
                    return null;
                }

                // 重命名：去掉 .tmp 后缀
                File finalFile = new File(tempFile.getParent(), fileName);
                if (finalFile.exists()) {
                    finalFile.delete();
                }
                if (tempFile.renameTo(finalFile)) {
                    String path = finalFile.getAbsolutePath();
                    reset();
                    return path;
                } else {
                    // rename 失败，直接用 tmp 文件
                    String path = tempFile.getAbsolutePath();
                    reset();
                    return path;
                }
            } catch (IOException e) {
                Log.e(TAG, "Finish file receive failed", e);
                reset();
                return null;
            }
        }

        /**
         * 重置状态，清理临时资源
         */
        void reset() {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                }
                outputStream = null;
            }
            if (tempFile != null && tempFile.exists() && outputStream == null && receivedSize > 0) {
                // 传输未完成的临时文件不删除，由 finish 或外部清理
            }
            fileName = null;
            fileSize = 0;
            receivedSize = 0;
            tempFile = null;
            digest = null;
        }

        boolean isReceiving() {
            return outputStream != null;
        }
    }
}
