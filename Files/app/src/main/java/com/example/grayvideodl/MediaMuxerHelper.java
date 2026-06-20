/*
 * MediaMuxerHelper.java
 * Android MediaMuxer 音视频合并辅助类
 * 
 * 功能：当 FFmpeg 在 Android 应用层不可用时，
 * 使用 Android 系统自带的 MediaMuxer + MediaExtractor API
 * 将分离的纯视频文件和纯音频文件合并为带声音的 MP4 文件。
 * 
 * 使用场景：
 * - Bilibili 等平台使用 DASH 协议分发视频（视频流和音频流分离）
 * - 应用层没有 FFmpeg 执行权限（SELinux/noexec 限制）
 * - 需要下载带声音的视频但无法调用外部 FFmpeg 合并
 * 
 * 原理：
 * 1. MediaExtractor 分别读取视频文件和音频文件的媒体轨道
 * 2. MediaMuxer 创建新的 MP4 容器
 * 3. 将视频轨道的样本数据写入新容器的视频轨道
 * 4. 将音频轨道的样本数据写入新容器的音频轨道
 * 5. 最终输出同时包含视频和音频的完整 MP4 文件
 */

package com.example.grayvideodl;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Android MediaMuxer 音视频合并辅助类
 * 负责将纯视频文件和纯音频文件合并为带声音的 MP4 文件
 */
public class MediaMuxerHelper {
    private static final String TAG = "MediaMuxerHelper";
    // 与 Python 端统一的 Logcat 标签，方便过滤
    private static final String TAG_FF_MEDIA = "FF-media";

    /**
     * 合并纯视频文件和纯音频文件为单个 MP4 文件
     * 
     * 使用 Android 系统自带的 MediaExtractor 读取视频轨道和音频轨道，
     * 然后用 MediaMuxer 将其写入同一个 MP4 容器中。
     * 
     * @param videoPath  纯视频文件路径（仅视频轨道，无音频）
     * @param audioPath  纯音频文件路径（仅音频轨道，无视频）
     * @param outputPath 输出 MP4 文件路径
     * @return true 表示合并成功，false 表示失败
     */
    public static boolean mergeVideoAudio(String videoPath, String audioPath, String outputPath) {
        Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 开始合并音视频");
        Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 视频文件=" + videoPath);
        Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 音频文件=" + audioPath);
        Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 输出文件=" + outputPath);

        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        MediaMuxer muxer = null;

        try {
            // ==============================
            // 阶段1：初始化视频提取器
            // ==============================
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(videoPath);
            int videoTrackIndex = selectTrack(videoExtractor, "video/");
            if (videoTrackIndex < 0) {
                Log.e(TAG, "视频文件中没有找到视频轨道");
                Log.e(TAG_FF_MEDIA, "MediaMuxerHelper: 视频文件中没有视频轨道");
                return false;
            }
            // 选择视频轨道，后续读取样本时只读取该轨道的数据
            videoExtractor.selectTrack(videoTrackIndex);
            // 获取视频轨道的格式信息（编码类型、分辨率、CSD等）
            MediaFormat videoFormat = videoExtractor.getTrackFormat(videoTrackIndex);
            Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 视频轨道信息: " + videoFormat.getString(MediaFormat.KEY_MIME)
                    + ", " + videoFormat.getInteger(MediaFormat.KEY_WIDTH) + "x"
                    + videoFormat.getInteger(MediaFormat.KEY_HEIGHT));

            // ==============================
            // 阶段2：初始化音频提取器
            // ==============================
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(audioPath);
            int audioTrackIndex = selectTrack(audioExtractor, "audio/");
            if (audioTrackIndex < 0) {
                Log.e(TAG, "音频文件中没有找到音频轨道");
                Log.e(TAG_FF_MEDIA, "MediaMuxerHelper: 音频文件中没有音频轨道");
                return false;
            }
            // 选择音频轨道
            audioExtractor.selectTrack(audioTrackIndex);
            // 获取音频轨道的格式信息（编码类型、采样率、声道数等）
            MediaFormat audioFormat = audioExtractor.getTrackFormat(audioTrackIndex);
            Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 音频轨道信息: " + audioFormat.getString(MediaFormat.KEY_MIME)
                    + ", 采样率=" + audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    + ", 声道=" + audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));

            // ==============================
            // 阶段3：初始化 Muxer（MP4 复用器）
            // ==============================
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // 向 Muxer 注册视频轨道和音频轨道，获取轨道索引
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            int muxerAudioTrack = muxer.addTrack(audioFormat);
            // 启动 Muxer，开始接收样本数据
            muxer.start();

            // ==============================
            // 阶段4：写入视频样本数据
            // ==============================
            Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 开始写入视频样本...");
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB 缓冲区
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long videoSampleCount = 0;

            // 将视频提取器定位到开头
            videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (true) {
                // 清除缓冲区，准备读取新样本
                buffer.clear();
                // 从视频提取器读取一个样本到缓冲区
                int sampleSize = videoExtractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    // 样本读取完毕
                    break;
                }
                // 设置样本元数据：偏移、大小、时间戳（微秒）、标志
                bufferInfo.set(0, sampleSize, videoExtractor.getSampleTime(),
                        videoExtractor.getSampleFlags());
                // 将样本写入 Muxer 的视频轨道
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo);
                videoSampleCount++;
                // 移动到下一个样本
                videoExtractor.advance();
            }
            Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 视频样本写入完成，共 " + videoSampleCount + " 个样本");

            // ==============================
            // 阶段5：写入音频样本数据
            // ==============================
            Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 开始写入音频样本...");
            long audioSampleCount = 0;

            // 将音频提取器定位到开头
            audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (true) {
                buffer.clear();
                int sampleSize = audioExtractor.readSampleData(buffer, 0);
                if (sampleSize < 0) {
                    break;
                }
                bufferInfo.set(0, sampleSize, audioExtractor.getSampleTime(),
                        audioExtractor.getSampleFlags());
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo);
                audioSampleCount++;
                audioExtractor.advance();
            }
            Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 音频样本写入完成，共 " + audioSampleCount + " 个样本");

            // ==============================
            // 阶段6：完成合并
            // ==============================
            muxer.stop();
            muxer.release();
            muxer = null;

            Log.i(TAG_FF_MEDIA, "MediaMuxerHelper: 音视频合并成功，输出=" + outputPath);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "音视频合并异常: " + e.getMessage(), e);
            Log.e(TAG_FF_MEDIA, "MediaMuxerHelper: 合并异常: " + e.getMessage());
            return false;
        } finally {
            // ==============================
            // 清理：释放所有资源
            // ==============================
            if (videoExtractor != null) {
                videoExtractor.release();
            }
            if (audioExtractor != null) {
                audioExtractor.release();
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                muxer.release();
            }
        }
    }

    /**
     * 在 MediaExtractor 中查找指定 MIME 类型的媒体轨道
     * 
     * @param extractor  媒体提取器实例
     * @param mimePrefix MIME 类型前缀（如 "video/" 或 "audio/"）
     * @return 轨道索引，未找到返回 -1
     */
    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        int trackCount = extractor.getTrackCount();
        for (int i = 0; i < trackCount; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }
}