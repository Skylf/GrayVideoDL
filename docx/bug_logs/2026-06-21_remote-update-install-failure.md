# Bug: 远程更新安装提示"签名冲突"或"安装包已损坏"

**日期**: 2026-06-21  
**版本**: v0.8D  
**优先级**: 高  

## 现象
1. 通过 Gitee 远程下载 APK 后安装，系统提示"安装包已损坏"
2. 使用 debug 版本 APK 后，提示"签名冲突，无法安装"
3. logcat 确认下载、解压、校验均通过，FileProvider URI 正确，但安装时 ColorOS 的 `oplus.romupdate` 拒绝安装

## 根因
### 安装包已损坏（第一阶段）
- 最开始上传的是 release 版本 APK（不含 debug.keystore 签名），但手机上安装的是 debug 版本，签名不一致
- ColorOS 的 Package Installer（`oplus.romupdate`）对 FileProvider 的 `content://` URI 兼容性不佳

### 签名冲突（第二阶段）
- 用 debug 版本 APK 替换后，仍然提示"签名冲突"
- 根源是 APK 内部 **versionCode / versionName 与已安装版本一致**（都是 versionCode=2, versionName=0.8C）
- Android 不允许覆盖安装同版本号的应用（某些 ROM 提示为"签名冲突"）

## 修复
### 涉及文件
1. **[build.gradle.kts](file:///d:/COMPUTER/Android/GrayTools/Files/app/build.gradle.kts)** — 统一版本号来源，versionCode=3→4, versionName="0.8D"→"0.8E"
2. **[UpdateManager.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/UpdateManager.java)** — 下载安装逻辑不变（已验证正确）

## 验证
1. 将 versionCode 从 2 提高到 3（0.8D），重新编译 debug APK 上传 Gitee
2. 手机上 debug 0.8C → 检测到 0.8D（versionCode 更高，签名一致）→ 安装成功
3. 确认远程更新全流程（检查 → 下载 → 解压 → 安装）可用