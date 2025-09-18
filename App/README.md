# EmpLab - 员工请假申请APP

## 📱 项目简介

EmpLab是一个基于Android原生开发的员工请假申请系统，提供简洁直观的用户界面，支持多种请假类型的申请流程。

## ✨ 功能特性

### 🏠 首页功能
- **搜索功能**：支持扫码、AI助手、文本搜索
- **功能网格**：包含待办、日历、请假、报销等常用功能
- **底部导航**：首页、人员、消息、我的四个主要模块

### 📝 请假申请流程
1. **请假类型选择**：支持年休假、病假、事假等多种类型
2. **时间选择**：日期选择器 + 时间段选择（全天/上午/下午）
3. **详细信息**：请假事由、前往地区、附件上传
4. **提交确认**：完整的申请流程和状态反馈

## 🛠️ 技术栈

- **开发语言**：Kotlin
- **UI框架**：Android原生View系统
- **构建工具**：Gradle 8.0
- **最低SDK**：API 24 (Android 7.0)
- **目标SDK**：API 33 (Android 13)
- **Java版本**：Java 11

## 📋 环境要求

### 开发环境
- **Android Studio**：最新稳定版本
- **Java JDK**：Java 11
- **Android SDK**：API 24-33
- **Gradle**：8.0（项目自动管理）

### 运行环境
- **Android设备**：Android 7.0 (API 24) 及以上
- **内存**：建议2GB以上
- **存储**：至少50MB可用空间

## 🚀 快速开始

### 1. 克隆项目
```bash
git clone https://github.com/Kyo0wind/EmpLab.git
cd EmpLab
```

### 2. 在Android Studio中打开
1. 启动Android Studio
2. 选择 "Open an existing project"
3. 选择克隆的 `EmpLab` 文件夹
4. 等待Gradle同步完成

### 3. 运行项目
1. 连接Android设备或启动模拟器
2. 点击 "Run" 按钮或使用快捷键 `Shift + F10`
3. 选择目标设备
4. 等待应用安装和启动

## 📁 项目结构

```
EmpLab/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/emplab/
│   │   │   ├── MainActivity.kt              # 主页面Activity
│   │   │   ├── LeaveApplicationActivity.kt  # 请假类型选择
│   │   │   ├── LeaveTimeActivity.kt         # 请假时间选择
│   │   │   └── LeaveDetailsActivity.kt      # 请假详情填写
│   │   ├── res/
│   │   │   ├── layout/                      # 布局文件
│   │   │   ├── drawable/                    # 图标和背景
│   │   │   ├── values/                      # 颜色、样式、字符串
│   │   │   └── color/                       # 颜色选择器
│   │   └── AndroidManifest.xml              # 应用清单
│   └── build.gradle.kts                     # 应用级构建配置
├── gradle/
│   ├── libs.versions.toml                   # 依赖版本管理
│   └── wrapper/                             # Gradle包装器
├── build.gradle.kts                         # 项目级构建配置
├── settings.gradle.kts                      # 项目设置
├── gradle.properties                        # Gradle属性
└── README.md                                # 项目说明
```

## 🎨 界面设计

### 主页面
- **顶部搜索区**：搜索框 + 扫码/AI助手按钮
- **功能网格**：3x4网格布局，包含各种功能图标
- **底部导航**：四个主要模块的切换

### 请假申请流程
- **步骤1**：请假类型选择列表
- **步骤2**：日期和时间段选择
- **步骤3**：详细信息填写和附件上传

## 🔧 配置说明

### 构建配置
- **编译SDK**：API 33
- **目标SDK**：API 33
- **最低SDK**：API 24
- **ViewBinding**：已启用
- **Gradle版本**：8.0
- **Java版本**：11

### 依赖管理
项目使用 `libs.versions.toml` 进行版本管理，主要依赖：
- `androidx.appcompat:appcompat:1.4.2`
- `com.google.android.material:material:1.6.1`
- `androidx.constraintlayout:constraintlayout:2.1.4`

## 🐛 常见问题

### Q: 构建失败，提示Java版本不兼容
**A:** 确保使用Java 11，检查 `gradle.properties` 中的 `org.gradle.java.home` 设置。项目使用Gradle 8.0，完全支持Java 11。

### Q: 无法找到SDK
**A:** 在Android Studio中设置SDK路径：`File > Project Structure > SDK Location`

### Q: Gradle同步失败
**A:** 检查网络连接，尝试清理项目：`Build > Clean Project`

### Q: 应用无法安装
**A:** 检查设备是否开启"未知来源"安装，或使用调试模式

## 📱 功能演示

### 主要功能流程
1. **启动应用** → 显示主页面
2. **点击"请休假"** → 进入请假类型选择
3. **选择"年休假"** → 进入时间选择页面
4. **选择日期和时间** → 进入详情填写页面
5. **填写信息并提交** → 显示提交成功提示

## 🤝 贡献指南

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 📞 联系方式

- **项目作者**：Kyo0wind
- **GitHub**：[https://github.com/Kyo0wind/EmpLab](https://github.com/Kyo0wind/EmpLab)
- **项目描述**：测试模拟APP

## 📝 更新日志

### v1.1.0 (2024-12-19)
- 🔄 升级Gradle从7.6.4到8.0
- 🔄 配置Java 11支持
- 🔄 优化.gitignore文件
- 🔄 更新项目文档

### v1.0.0 (2024-12-19)
- ✅ 完成主页面UI设计
- ✅ 实现请假申请完整流程
- ✅ 支持多种请假类型
- ✅ 添加日期和时间选择功能
- ✅ 实现详情填写和附件上传
- ✅ 完成Git版本控制配置

---

**注意**：这是一个测试模拟APP，仅用于演示和学习目的。
