/*
 * VideoInfo.java
 * 视频信息数据模型，用于解析 yt-dlp 返回的 JSON 数据。
 * 包含视频标题、时长、缩略图、可用格式列表等信息。
 */

package com.example.grayvideodl.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/*
 * 视频信息模型类
 * 负责将 Python 桥接模块返回的 JSON 字符串解析为 Java 对象
 */
public class VideoInfo {

    // 视频标题
    private String title;

    // 视频时长（秒）
    private double duration;

    // 缩略图 URL
    private String thumbnail;

    // 可用格式列表
    private List<Format> formats;

    // 解析状态："ok" 或 "error"
    private String status;

    // 错误信息（解析失败时填充）
    private String error;

    /*
     * 视频格式子类
     * 描述单个可用的视频或音频格式
     */
    public static class Format {
        // 格式 ID，用于 yt-dlp 下载时指定格式
        private String formatId;

        // 文件扩展名：mp4、m4a、webm 等
        private String ext;

        // 分辨率描述：如 "1920x1080"、"audio only"
        private String resolution;

        // 文件大小（字节），-1 表示未知
        private long filesize;

        // 视频编码格式
        private String vcodec;

        // 音频编码格式
        private String acodec;

        // 格式备注（如 "1080P 高清"），来自 yt-dlp 的 format_note
        private String formatNote;

        // 格式是否被锁定（需付费会员才能下载）
        private boolean isLocked;

        public Format(String formatId, String ext, String resolution,
                      long filesize, String vcodec, String acodec,
                      String formatNote, boolean isLocked) {
            this.formatId = formatId;
            this.ext = ext;
            this.resolution = resolution;
            this.filesize = filesize;
            this.vcodec = vcodec;
            this.acodec = acodec;
            this.formatNote = formatNote;
            this.isLocked = isLocked;
        }

        /*
         * 判断是否为纯音频格式
         * 视频编码为 "none" 表示该格式只有音频流
         */
        public boolean isAudioOnly() {
            return "none".equals(vcodec);
        }

        // Getter 方法
        public String getFormatId() { return formatId; }
        public String getExt() { return ext; }
        public String getResolution() { return resolution; }
        public long getFilesize() { return filesize; }
        public String getVcodec() { return vcodec; }
        public String getAcodec() { return acodec; }
        public String getFormatNote() { return formatNote; }
        public boolean isLocked() { return isLocked; }

        /*
         * getFilesizeText: 格式化文件大小显示
         * 将字节数转换为可读的 KB/MB 字符串
         */
        public String getFilesizeText() {
            if (isLocked) return "需大会员";
            if (filesize <= 0) return "未知大小";
            if (filesize < 1024 * 1024) {
                return String.format("%.0f KB", filesize / 1024.0);
            }
            return String.format("%.1f MB", filesize / (1024.0 * 1024.0));
        }

        /*
     * getResolutionDisplay: 获取分辨率显示文本
     * "640x360" → "360p"，"1920x1080" → "1080p"
     * 对于锁定格式，附加锁定标记
     */
    public String getResolutionDisplay() {
        // 优先使用 format_note（如 "1080P 高清"）
        String display;
        if (formatNote != null && !formatNote.isEmpty()) {
            display = formatNote;
        } else {
            // 从 resolution 提取高度，转为 "360p" 格式
            display = resolutionToQuality(resolution);
        }
        if (isLocked) {
            display = display + " 🔒";
        }
        return display;
    }

    /*
     * resolutionToQuality: "640x360" → "360p"
     */
    public static String resolutionToQuality(String resolution) {
        if (resolution == null || resolution.isEmpty()
                || resolution.contains("audio")) {
            return resolution;
        }
        try {
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                int height = Integer.parseInt(parts[1].trim());
                if (height >= 2160) return "4K";
                if (height >= 1440) return "2K";
                if (height >= 1080) return "1080p";
                if (height >= 720)  return "720p";
                if (height >= 480)  return "480p";
                if (height >= 360)  return "360p";
                return height + "p";
            }
        } catch (NumberFormatException ignored) {
        }
        return resolution;
    }
    }

    // 构造方法
    public VideoInfo() {
        this.formats = new ArrayList<>();
        this.status = "ok";
        this.error = "";
    }

    /*
     * parseFromJson: 从 yt-dlp 返回的 JSON 字符串解析为 VideoInfo 对象
     * 如果解析失败，返回一个 status 为 "error" 的 VideoInfo 对象
     *
     * @param jsonString: yt-dlp 桥接模块返回的 JSON 字符串
     * @return VideoInfo 对象
     */
    public static VideoInfo parseFromJson(String jsonString) {
        // 创建一个空的 VideoInfo 对象用于填充数据
        VideoInfo info = new VideoInfo();

        try {
            // 将 JSON 字符串解析为 JSONObject
            JSONObject jsonObject = new JSONObject(jsonString);

            // 解析基本字段
            info.setStatus(jsonObject.optString("status", "error"));
            info.setError(jsonObject.optString("error", ""));
            info.setTitle(jsonObject.optString("title", ""));
            info.setDuration(jsonObject.optDouble("duration", 0));
            info.setThumbnail(jsonObject.optString("thumbnail", ""));

            // 解析格式列表
            JSONArray formatsArray = jsonObject.optJSONArray("formats");
            if (formatsArray != null) {
                // 遍历 JSON 数组，逐个解析格式对象
                for (int i = 0; i < formatsArray.length(); i++) {
                    // 获取单个格式的 JSON 对象
                    JSONObject fmtObj = formatsArray.getJSONObject(i);
                    // 从 JSON 对象中提取格式字段
                    Format fmt = new Format(
                            fmtObj.optString("format_id", ""),
                            fmtObj.optString("ext", ""),
                            fmtObj.optString("resolution", ""),
                            fmtObj.optLong("filesize", -1),
                            fmtObj.optString("vcodec", ""),
                            fmtObj.optString("acodec", ""),
                            fmtObj.optString("format_note", ""),
                            fmtObj.optBoolean("is_locked", false)
                    );
                    // 将格式对象添加到列表中
                    info.getFormats().add(fmt);
                }
            }

        } catch (Exception parseException) {
            // JSON 解析失败时设置错误状态
            info.setStatus("error");
            info.setError("数据解析失败: " + parseException.getMessage());
        }

        return info;
    }

    /*
     * aggregateByQuality: 按画质聚合格式列表，每种画质只保留一个最佳格式
     * yt-dlp 对同一分辨率会返回多种编码格式（h264/h265/AV1）和不含音频的纯视频流，
     * 该方法按画质分组去重：
     *   1. 跳过纯音频格式
     *   2. 同一画质下优先选择非锁定、合并格式（含音频）、最佳编码、文件最大的格式
     * @return 去重后的视频格式列表
     */
    public List<Format> aggregateByQuality() {
        // 使用 LinkedHashMap 保持插入顺序，键为画质名称（如 "360p""720p""1080p"）
        java.util.LinkedHashMap<String, Format> bestByQuality =
                new java.util.LinkedHashMap<>();

        for (Format fmt : formats) {
            // 跳过纯音频格式（acodec != none 但 vcodec == none）
            if (fmt.isAudioOnly()) continue;

            // 获取画质键值（如 "360p""720p""1080p"），去除锁定标记
            // resolutionToQuality 为 Format 类的静态方法，需通过类名调用
            String qualityKey = Format.resolutionToQuality(fmt.getResolution());

            Format existing = bestByQuality.get(qualityKey);
            if (existing == null) {
                // 该画质尚无代表格式，直接放入
                bestByQuality.put(qualityKey, fmt);
            } else {
                // 已有代表格式，比较并保留更优的一个
                    Format better = pickBetterFormat(existing, fmt);
                    bestByQuality.put(qualityKey, better);
                }
            }

            return new ArrayList<>(bestByQuality.values());
        }

    /*
     * pickBetterFormat: 比较两个同画质的格式，返回更优的一个
     * 优先级规则（编号越小优先级越高）：
     *   1. 非锁定格式 > 锁定格式，
     *   2. 合并格式（同时含音视频）> 纯视频格式（acodec=none）
     *   3. 文件大小大的 > 文件大小小的（码率更高）
     * @param a 格式A
     * @param b 格式B
     * @return 更优的格式
     */
    private static Format pickBetterFormat(Format a, Format b) {
        // 规则1：非锁定优于锁定
        if (a.isLocked() && !b.isLocked()) return b;
        if (!a.isLocked() && b.isLocked()) return a;

        // 规则2：合并格式优于纯视频格式（acodec != "none" 表示含音频）
        boolean aIsMerged = !"none".equals(a.getAcodec());
        boolean bIsMerged = !"none".equals(b.getAcodec());
        if (aIsMerged && !bIsMerged) return a;
        if (!aIsMerged && bIsMerged) return b;

        // 规则3：文件大小大的优先（码率更高，画质更好）
        if (a.getFilesize() > b.getFilesize()) return a;
        if (b.getFilesize() > a.getFilesize()) return b;

        // 所有规则平局时默认返回A
        return a;
    }

    /*
     * getDurationText: 获取格式化的时长文本
     * 将秒数转换为 "分:秒" 格式
     */
    public String getDurationText() {
        int totalSeconds = (int) duration;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d分%d秒", minutes, seconds);
    }

    // Getter 和 Setter 方法
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public double getDuration() { return duration; }
    public void setDuration(double duration) { this.duration = duration; }

    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }

    public List<Format> getFormats() { return formats; }
    public void setFormats(List<Format> formats) { this.formats = formats; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public boolean isSuccess() { return "ok".equals(status); }
}
