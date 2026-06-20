# Bug: 抖音图文链接解析失败无友好提示

**日期**: 2026-06-13  
**版本**: v0.6A  
**优先级**: 中  

## 现象

解析抖音图文链接（如 `https://www.douyin.com/note/7650380007441599208`）时，返回错误：
```
ERROR: Unsupported URL: `https://www.douyin.com/note/xxx`
```

用户只看到错误信息，不知道这是因为图文链接没有视频资源。

## 根因

抖音有两种内容类型：
1. **视频**：可以正常解析和下载
2. **图文**：只有图片和文字，没有视频资源，yt-dlp 无法处理

之前的代码没有检测图文链接，导致用户看到的是技术性错误信息。

## 修复

### 1. 解析前检测
在 `doParse()` 方法中，解析前检测 URL 是否包含 `/note/`（抖音图文链接特征）：
- 如果是图文链接，直接弹出橙色警告并取消解析

### 2. 解析失败后检测
如果解析失败且错误信息包含 "Unsupported URL"，再次检测是否为抖音链接：
- 如果是抖音链接，弹出图文警告
- 否则显示原始错误信息

### 3. 新增警告方法
新增 `showImageTextWarning()` 方法，显示橙色警告浮层：
- 消息：无法提取视频资源！抖音图文链接不存在视频资源
- 6 秒后自动消失

## 修改文件

- [HomeFragment.java](file:///d:/COMPUTER/Android/GrayTools/Files/app/src/main/java/com/example/grayvideodl/ui/home/HomeFragment.java)
  - `doParse()`: 添加图文链接检测
  - 新增 `showImageTextWarning()` 方法

## 验证

- 输入抖音图文链接（如 `https://www.douyin.com/note/xxx`）
- 应弹出橙色警告：无法提取视频资源！抖音图文链接不存在视频资源
- 6 秒后警告自动消失