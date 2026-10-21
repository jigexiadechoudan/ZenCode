---
name: test
description: 运行项目测试并报告结果
mode: inline
allowedTools: [Bash, ReadFile, Glob, Grep]
---
运行当前项目的测试并报告结果，$ARGUMENTS 可传入指定测试类或过滤参数。

步骤：
1. 识别构建工具：查看项目根目录的 pom.xml / build.gradle / package.json / Cargo.toml 等。
2. 用合适的命令运行测试（如 Maven 的 `mvn test`、npm 的 `npm test`）。
3. 观察测试结果：通过的用例数、失败/错误数、耗时。
4. 若有失败：读取失败信息定位失败的测试类与方法，必要时阅读相关源码解释原因。
5. 向用户报告：总体结论（通过/部分失败）、通过数、失败项清单、失败原因与可能的修复方向。

额外要求：
- 若 $ARGUMENTS 非空，把它作为测试过滤参数（如指定测试类）传给构建命令。
- 输出保持简洁，失败详情只列关键信息。
