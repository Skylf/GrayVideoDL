# Bug: BuildConfig 符号找不到导致编译失败

**日期**: 2026-06-21
**版本**: v0.8C
**优先级**: 高

## 现象
编译时 SettingsFragment.java 报错：
```
错误: 找不到符号
符号:   类 BuildConfig
位置: 程序包 com.example.grayvideodl
```

## 根因
`BuildConfig` 是 Gradle 编译期自动生成的类，需要在 Gradle 同步/构建后才会存在。某些编译环境中（特别是增量编译或首次打开项目时），BuildConfig 尚未生成，导致引用它的代码编译失败。

## 修复
将 `SettingsFragment.java` 和 `UpdateManager.java` 中对 `BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE` 的引用，全部替换为通过 `PackageManager` 运行时读取：

**SettingsFragment.java** ([restoreSettings:91](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/settings/SettingsFragment.java#L91-L99)):
```java
// Before:
tvVersion.setText("v" + com.example.grayvideodl.BuildConfig.VERSION_NAME);

// After:
String versionName = "0.8C";
try {
    versionName = requireContext().getPackageManager()
            .getPackageInfo(requireContext().getPackageName(), 0).versionName;
} catch (Exception e) { ... }
tvVersion.setText("v" + (versionName != null ? versionName : "0.8C"));
```

**UpdateManager.java** ([checkUpdate:167](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/UpdateManager.java#L167-L175)):
- `checkUpdate()` 中内部版本比较改用 `context.getPackageManager()...`
- `getLocalVersionName()` 和 `getLocalVersionCode()` 静态方法改为接收 `Context` 参数

## 验证
- 重新编译项目，不再出现 BuildConfig 相关错误
- 版本号在运行时通过 PackageManager 读取，结果与 BuildConfig 一致
- 后续升级版本时只需改 build.gradle.kts 一处即可