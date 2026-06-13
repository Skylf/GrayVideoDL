# Bug: VideoInfo.java 中 aggregateByQuality 方法缺少闭合花括号

**日期**: 2026-06-10  
**版本**: v0.0.1F  
**优先级**: 高  

## 现象
编译 VideoInfo.java 时报错：
```
D:\...\VideoInfo.java:263: 错误: 非法的表达式开始
     private static Format pickBetterFormat(Format a, Format b) {
     ^
```

## 根因
`aggregateByQuality()` 方法（第 227 行）的 `for` 循环（第 232 行）在第 250 行闭合后，缺少一个 `}` 来闭合方法本身。导致第 263 行的 `pickBetterFormat()` 被编译器认为是定义在 `aggregateByQuality()` 方法内部，从而报"非法的表达式开始"。

## 修复
在文件 [VideoInfo.java](../../Files/app/src/main/java/com/example/grayvideodl/model/VideoInfo.java) 中：
- 在 `for` 循环的闭合括号 `}` 后添加了方法的闭合括号 `}`
- 将 `return` 语句移到 `for` 循环外部、方法内部
- 调整了相关缩进

具体修改位置：`aggregateByQuality()` 方法的 else 块 → for 循环 → 方法体的花括号层级。

## 验证
编译 VideoInfo.java 不再报"非法的表达式开始"错误，`aggregateByQuality()` 和 `pickBetterFormat()` 方法均能正常识别。
