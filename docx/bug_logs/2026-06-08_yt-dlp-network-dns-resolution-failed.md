# Bug: yt-dlp 网络 DNS 解析失败

**日期**: 2026-06-08
**版本**: v1.0.0
**优先级**: 高

## 现象
用户输入 B站 视频链接（`b23.tv` 短链 和 `bilibili.com` 长链）点击"解析视频"后，日志面板显示：
```
视频信息提取失败: ERROR: [generic] Unable to download webpage: 
[Errno 7] No address associated with hostname 
(caused by TransportError('[Errno 7] No address associated with hostname'))
```

环境检测（`testEnvironment`）能正常运行，显示 Python 3.11.14 和 yt-dlp 2026.03.17 安装成功，说明 Chaquopy Python 环境正常。但实际网络请求时 DNS 解析失败。

## 根因
Android 应用的 `AndroidManifest.xml` 中缺少以下关键声明：
1. **`<uses-permission android:name="android.permission.INTERNET" />`** — 网络访问权限
2. **`<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`** — 网络状态检测权限
3. **`android:usesCleartextTraffic="true"`** — 允许明文流量（部分视频站点使用 HTTP）

Android 10+（API 29）默认不允许应用发起网络请求，即使是通过 Chaquopy 嵌入的 Python 环境，其底层的 socket 调用也需要应用级别的网络权限。缺少 INTERNET 权限时，Python 的 urllib/requests 等库会无法解析 DNS，导致 `[Errno 7] No address associated with hostname` 错误。

## 修复
修改文件：`Files/app/src/main/AndroidManifest.xml`

- 在 `<manifest>` 下添加：
  ```xml
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  ```

- 在 `<application>` 标签中添加：
  ```xml
  android:usesCleartextTraffic="true"
  ```

## 验证
1. 重新编译安装 APK：
   ```
   .\gradlew.bat :app:compileDebugJavaWithJavac
   BUILD SUCCESSFUL
   ```
2. 在手机上输入任意视频链接（如 B站、抖音等），点击"解析视频"。
3. 正常情况下应能成功返回视频标题、格式列表等信息，不再出现 DNS 解析错误。
