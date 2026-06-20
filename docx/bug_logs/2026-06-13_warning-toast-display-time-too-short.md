# Bug: 平台兼容性警告浮层显示时间过短

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 中  

## 现象

当检测到无内置提取器平台（如快手）的链接时，由于解析时间过短，橙色警告浮层刚显示就被解析成功的提示覆盖，用户无法看到警告内容。

## 根因

两个浮层存在时序冲突：
1. 警告浮层显示（6秒后自动消失）
2. 解析很快完成（可能小于1秒）
3. 成功提示浮层立即显示
4. 由于两个浮层都在顶部，成功提示会覆盖警告提示

## 修复

采用两步优化方案：

### 1. 布局层面：警告浮层显示在最上层
修改 `fragment_home.xml`：
- 将警告浮层的 `elevation` 从 4dp 提升到 8dp（高于成功提示的 4dp）
- 将警告浮层固定在顶部（`layout_constraintTop_toTopOf="parent"`）

### 2. 逻辑层面：成功提示等待警告消失后再显示
修改 `HomeFragment.java`：
- 修改 `showSuccessToast()` 方法，检查警告浮层是否正在显示
- 如果警告浮层可见，延迟 7 秒（警告显示 6 秒 + 1 秒缓冲）后再显示成功提示
- 将原有的显示逻辑提取为 `showSuccessToastInternal()` 内部方法

## 修改文件

### 1. 布局文件
- [fragment_home.xml](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/res/layout/fragment_home.xml)
  - `layout_platform_warning_toast` 的 `elevation` 改为 8dp
  - 约束条件改为 `layout_constraintTop_toTopOf="parent"`

### 2. Java 代码
- [HomeFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/home/HomeFragment.java)
  - 修改 `showSuccessToast()` 方法，添加警告浮层检测逻辑
  - 新增 `showSuccessToastInternal()` 方法

## 验证

- 输入快手链接，警告浮层应正常显示 6 秒
- 解析完成后，成功提示应在警告消失后 1 秒左右显示
- 两个浮层不再互相覆盖，用户可以清晰看到两个提示信息