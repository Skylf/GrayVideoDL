# Bug: 版本号多处硬编码不一致

**日期**: 2026-06-21
**版本**: v0.8C
**优先级**: 高

## 现象
- build.gradle.kts 中 `versionName = "0.8B"`
- strings.xml 中 `version_value = "v0.7A"`（过时）
- fragment_settings.xml 中 `android:text="v0.7A"`（硬编码）
- 三处版本号各自独立，升级时容易遗漏，导致设置页面显示的版本号与实际构建版本不一致

## 根因
项目早期没有建立统一的版本号管理机制，版本号在多个地方被硬编码，每次升级需要手动同步所有位置的版本号，极易遗漏。

## 修复
1. **build.gradle.kts**: `versionName = "0.8C"`，作为全局唯一的版本号来源
2. **strings.xml**: 移除 `version_value` 条目
3. **fragment_settings.xml**: 将 `android:text="v0.7A"` 改为 `android:text=""`，交由代码动态填充
4. **SettingsFragment.java**: 在 `restoreSettings()` 中通过 `BuildConfig.VERSION_NAME` 动态读取版本号：
   ```java
   tvVersion.setText("v" + BuildConfig.VERSION_NAME);
   ```

## 验证
- 编译后设置页面的版本号将自动与 build.gradle.kts 中的 `versionName` 保持一致
- 后续升级版本时，只需修改 `build.gradle.kts` 一处即可