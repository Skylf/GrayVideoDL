# Bug: 下载路径显示错误与打开文件夹跳转错误

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 高  

## 现象

1. 下载完成后，视频实际保存在公共目录 `/storage/emulated/0/Download/GrayVideoDL/`
2. 但下载列表中显示的路径却是私有目录 `/storage/emulated/0/Android/data/com.example.grayvideodl/files/Download/GrayVideoDL/`
3. 点击"打开文件夹"按钮，跳转到的是空目录（私有目录不存在该文件夹）

## 根因

**流程设计问题**：
1. 视频先下载到私有目录（`getExternalFilesDir`）
2. 下载完成后，`copyToPublicDownloads()` 将文件复制到公共目录
3. **但 `filepath` 字段仍保持为私有目录路径**，没有更新为公共目录路径
4. `getFolderToOpen()` 基于 `filepath` 返回文件夹路径，导致打开错误的目录

## 修复

### 修改 `copyToPublicDownloads()` 方法
- 修改返回类型为 `String`，返回复制后的公共目录文件路径
- 复制成功后构建公共目录路径并返回

### 修改 `executeDownload()` 方法
- 在调用 `copyToPublicDownloads()` 后检查返回值
- 如果复制成功，将 `filepath` 更新为公共目录路径


- 这样 UI 显示的路径和打开文件夹功能都会指向正确的公共目录

## 修改文件

| 文件 | 变更 |
|------|------|
| [DownloadFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/download/DownloadFragment.java) | 修改 `copyToPublicDownloads()` 返回文件路径，修改 `executeDownload()` 更新 filepath |

## 验证

- 下载视频后，列表项显示的路径应正确指向 `/storage/emulated/0/Download/GrayVideoDL/`
- 点击"打开文件夹"按钮，应打开包含视频文件的公共目录
- 视频文件应在文件管理器中可见