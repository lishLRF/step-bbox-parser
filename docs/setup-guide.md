# 安装部署指南

## 1. 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21+ | 后端运行时 |
| Node.js | 20+ | 前端构建 |
| Maven | 3.9+ | 后端构建（或用项目内 mvnw） |
| conda | 任意 | Python 环境管理 |

## 2. 一次性安装

### 2.1 Python 几何环境（首次，~20 分钟）

```bash
# 在 Anaconda Prompt（管理员）中运行
conda config --prepend envs_dirs "Z:\conda_envs"
conda create -y -n step-mesh -c conda-forge --override-channels -c conda-forge \
    python=3.11 cadquery trimesh numpy networkx
```

验证：
```bash
Z:\conda_envs\step-mesh\python.exe -c "import cadquery, trimesh; print('ok')"
```

### 2.2 前端依赖

```bash
cd Z:\Project\3Dbox-step\Software\frontend
npm install
```

## 3. 日常启动

### 方式一：一键启动（推荐）

```bash
cd Z:\Project\3Dbox-step\Software
node scripts\dev.mjs
```

自动完成：
1. 构建后端 jar（如需）
2. `java -jar` 启动后端（自动选空闲端口）
3. 等后端就绪（读 `.port` 文件）
4. `npm run dev` 启动前端
5. 打印前端 URL

**停止**：Ctrl+C，后端通过 `/actuator/shutdown` 优雅停止。

### 方式二：手动分开启动

**终端 1 — 后端**：
```bash
cd Z:\Project\3Dbox-step\Software\backend
mvn clean compile
java -jar target\step-bbox-parser-0.1.0-SNAPSHOT.jar
```

**终端 2 — 前端**：
```bash
cd Z:\Project\3Dbox-step\Software\frontend
npm run dev
```

## 4. 配置

所有配置在 `backend/src/main/resources/application.yml`：

```yaml
# 缓存目录（.STP 原文、bbox JSON、GLB 网格）
parser:
  upload-dir: Z:/Project/3Dbox-step/cache/uploads
  work-dir: Z:/Project/3Dbox-step/cache/work

# Python 环境 + 脚本路径
mesh:
  python-exe: Z:/conda_envs/step-mesh/python.exe
  script: scripts/step_to_mesh.py
  bbox-script: scripts/step_to_bbox.py
```

可用环境变量覆盖：
- `MESH_PYTHON_EXE` — Python 解释器路径
- `MESH_SCRIPT` — mesh 转换脚本路径
- `MESH_BBOX_SCRIPT` — bbox 计算脚本路径

## 5. 缓存管理

缓存位置：`Z:\Project\3Dbox-step\cache\`

| 子目录 | 内容 |
|---|---|
| `uploads/` | 上传的 .stp 原文 |
| `work/.port` | 后端端口号 |
| `step-bbox-bboxes/` | OCCT bbox JSON |
| `step-bbox-meshes/` | GLB 网格 |

清理旧缓存（>24 小时）：
```bash
curl -X DELETE "http://localhost:端口/api/cache?maxAgeHours=24"
```

手动清理：
```bash
del Z:\Project\3Dbox-step\cache\uploads\*
del Z:\Project\3Dbox-step\cache\step-bbox-bboxes\*
del Z:\Project\3Dbox-step\cache\step-bbox-meshes\*
```

## 6. 常见问题

### 后端启动报 ClassNotFoundException
```bash
cd backend
mvn clean compile
```
然后重新启动。

### 前端报 mesh 生成失败
检查 `Z:\conda_envs\step-mesh\python.exe` 是否存在：
```bash
Z:\conda_envs\step-mesh\python.exe -c "import OCP, trimesh; print('ok')"
```

### 端口冲突
不会出现——后端用 `server.port=0` 自动选空闲端口。

### 上传大文件超时
已移除所有超时限制。上传 + OCCT 解析可能需要 3-10 分钟（取决于模型大小）。进度条会在左栏实时显示。

### node dev.mjs 报 spawn EINVAL
Windows 上 Node 24 需要 `shell:true` 调用 `.cmd` 文件。已在 `dev.mjs` 中处理。
