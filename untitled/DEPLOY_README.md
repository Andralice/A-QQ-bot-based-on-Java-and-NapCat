# QQ Bot 自动部署脚本

## 概述

`deploy.sh` 是一个自动化脚本，用于将 QQ Bot 项目构建并部署到云端服务器。参考了现有项目的部署脚本，采用更健壮的部署流程。

部署参数直接在脚本中配置，支持环境变量覆盖。

## 部署流程

脚本执行以下步骤：

1. **Maven 打包**: 构建项目 JAR 文件
2. **确保远程目录**: 创建服务器部署目录
3. **上传 JAR**: 将新 JAR 上传到服务器
4. **停止旧实例**: 停止现有的 screen 会话和相关进程
5. **替换 JAR**: 备份旧 JAR 并替换新 JAR
6. **启动服务**: 在新的 screen 会话中启动服务

## 配置参数

脚本中的关键配置：

```bash
JAR_NAME="untitled-1.0-SNAPSHOT.jar"    # JAR 文件名
REMOTE_USER="root"                      # 服务器用户名
REMOTE_HOST="154.8.213.134"            # 服务器IP
REMOTE_DIR="/opt/qq-bot"               # 部署目录
SCREEN_NAME="qq-bot"                   # screen 会话名
```

## 环境变量

支持以下环境变量（可选，用于覆盖默认值）：

- `DB_PASS`: 数据库密码
- `BAILIAN_API_KEY`: Bailian API 密钥
- `AGENT_API_KEY`: Agent API 密钥
- `JAVA_OPTS`: JVM 参数

## 前置要求

### 本地环境
- Java 17+
- Maven
- SSH 客户端
- `sshpass` 工具（用于密码认证）
  - Ubuntu/Debian: `sudo apt install sshpass`
  - CentOS/RHEL: `sudo yum install sshpass`
- Git Bash (Windows) 或 Bash (Linux/macOS)

### 服务器环境
- Linux 服务器
- Java 17+ 运行环境
- `screen` 工具 (`apt install screen` 或 `yum install screen`)
- SSH 服务启用，允许密码登录

## 使用方法

### 1. 安装依赖工具

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install sshpass

# CentOS/RHEL
sudo yum install sshpass
```

### 2. 设置环境变量（可选）

```bash
export DB_PASS="your_db_password"
export BAILIAN_API_KEY="your_bailian_key"
export AGENT_API_KEY="your_agent_key"
```

### 3. 运行部署

```bash
./deploy.sh
```

## 管理服务

### 查看服务状态
```bash
sshpass -p 123456 ssh root@154.8.213.134 "screen -list"
```

### 进入 screen 会话
```bash
sshpass -p 123456 ssh root@154.8.213.134 "screen -r qq-bot"
```

### 查看日志
```bash
sshpass -p 123456 ssh root@154.8.213.134 "tail -n 100 /opt/qq-bot/qq-bot.log"
```

### 停止服务
```bash
sshpass -p 123456 ssh root@154.8.213.134 "screen -S qq-bot -X quit"
```

## 故障排除

### 常见问题

1. **sshpass 未找到**
   - 安装 sshpass: `sudo apt install sshpass`

2. **SSH 连接失败**
   - 检查服务器IP、用户名、密码
   - 确保服务器允许密码登录

3. **构建失败**
   - 确保本地有 Java 和 Maven
   - 检查项目依赖

4. **服务启动失败**
   - 检查服务器 Java 版本
   - 查看日志文件
   - 确认数据库连接和 API 密钥

5. **权限问题**
   - 确保部署目录有写入权限
   - 检查 screen 命令权限

### 日志位置

- 服务器日志: `/opt/qq-bot/qq-bot.log`
- 本地构建日志: 控制台输出

## 安全注意事项

- 不要在脚本中硬编码敏感信息
- 使用环境变量传递密码和 API 密钥
- 定期轮换 API 密钥
- 限制服务器 SSH 访问