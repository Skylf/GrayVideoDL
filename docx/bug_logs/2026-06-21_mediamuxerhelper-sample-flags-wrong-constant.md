# Bug: MediaMuxerHelper 样本标志类型不匹配

**日期**: 2026-06-21  
**版本**: v0.9A  
**优先级**: 高  

## 现象
执行 `./gradlew lint` 时，Lint 报告 2 个 `WrongConstant` 错误，导致 Lint 构建失败。

具体错误位置：
- [MediaMuxerHelper.java:133](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/MediaMuxerHelper.java): `videoExtractor.getSampleFlags()` 作为 `MediaCodec.BUFFER_FLAG_*` 参数传入 `bufferInfo.set()`
- [MediaMuxerHelper.java:157](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/MediaMuxerHelper.java): 同上，音频提取器

## 根因
`MediaExtractor.getSampleFlags()` 返回的是 `MediaExtractor.SAMPLE_FLAG_*` 常量（`SAMPLE_FLAG_SYNC=2`, `SAMPLE_FLAG_ENCRYPTED=4`, `SAMPLE_FLAG_PARTIAL_FRAME=1`），而 `MediaCodec.BufferInfo.set()` 的 flags 参数期望的是 `MediaCodec.BUFFER_FLAG_*` 常量（`BUFFER_FLAG_KEY_FRAME=1`, `BUFFER_FLAG_CODEC_CONFIG=2` 等）。两者虽然数值上部分重叠（`SAMPLE_FLAG_SYNC=2` 和 `BUFFER_FLAG_CODEC_CONFIG=2`），但语义不同，直接传递会导致 Lint 报错，且在某些设备上可能引发运行时行为异常。

## 修复
修改了 [MediaMuxerHelper.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/MediaMuxerHelper.java) 中的视频和音频样本写入逻辑：

1. **视频样本标志转换**（第 131-139 行）：在视频循环内，将 `videoExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC` 检查结果映射为 `MediaCodec.BUFFER_FLAG_KEY_FRAME`
2. **音频样本标志转换**（第 162-166 行）：同样的转换逻辑应用于音频循环

转换规则：`MediaExtractor.SAMPLE_FLAG_SYNC`（同步帧/关键帧）→ `MediaCodec.BUFFER_FLAG_KEY_FRAME`，非同步帧的 flags 设为 0。

## 验证
重新运行 `./gradlew lint`，构建成功，0 错误，Lint 不再报告 `WrongConstant` 相关问题。