# STEP 包围盒解析查看器 — 架构设计文档

## 1. 系统概述

本软件是一个 **STEP（ISO-10303-21）三维 CAD 装配体解析与可视化工具**，核心功能：

1. 上传 `.stp` / `.step` 文件
2. 解析装配树（部件/零件层级关系）
3. 计算每个零件的包围盒（AABB，轴对齐长方体）
4. 三栏展示：装配树 | 整机真实几何 | 包围盒骨架

### 1.1 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 后端 | Java + Spring Boot | Java 21, Spring Boot 3.3 |
| 几何引擎 | OCP (Open CASCADE Python 绑定) | OCCT 7.9 |
| 网格处理 | trimesh + numpy | trimesh 4.x |
| 前端 | React + TypeScript | React 18 |
| 3D 渲染 | Three.js + @react-three/fiber + drei | Three 0.169 |
| 构建 | Maven (后端) + Vite (前端) | |
| Python 环境 | conda (隔离环境 step-mesh) | Python 3.11 |

### 1.2 目录结构

```
Software/
├── backend/                          # Java 后端
│   ├── pom.xml
│   ├── src/main/java/com/cadbbox/parser/
│   │   ├── StepBboxParserApplication.java   # Spring Boot 入口
│   │   ├── step/                            # STEP 文本解析器
│   │   │   ├── StepParser.java              # 实体级流式解析（括号深度追踪）
│   │   │   ├── StepEntity.java              # 实体记录（Ref/InlineList/String）
│   │   │   └── StepNameCodec.java           # \X2\ UTF-16 解码
│   │   ├── tree/                            # 装配树构建
│   │   │   ├── AssemblyTreeBuilder.java     # NAUO + IDT + CDSR 链
│   │   │   ├── AssemblyNode.java            # 内部树节点
│   │   │   └── Transform4.java              # 4x4 齐次变换矩阵
│   │   ├── bbox/                            # 包围盒计算
│   │   │   ├── BoundingBox.java             # AABB 记录
│   │   │   ├── BoundingBoxCalculator.java   # 文本解析 fallback
│   │   │   ├── BboxIndexer.java             # 单遍索引构建
│   │   │   └── StepExporter.java            # 导出骨架 STEP（AP214）
│   │   ├── config/
│   │   │   └── PortPublisher.java           # 动态端口 + .port 文件
│   │   └── web/                             # REST API + 服务层
│   │       ├── ModelService.java            # 核心编排（上传/解析/缓存）
│   │       ├── MeshService.java             # GLB 网格生成
│   │       ├── AnnotationStore.java         # 重命名/合并持久化
│   │       ├── ParsedModel.java             # 内存缓存模型
│   │       └── api/ModelController.java     # REST 端点
│   ├── src/main/resources/application.yml   # 配置
│   └── src/test/                            # 单元测试（12 个）
├── frontend/                         # React 前端
│   ├── package.json
│   ├── vite.config.ts                # Vite + 动态端口
│   ├── src/
│   │   ├── components/
│   │   │   ├── ModelUploader.tsx     # 上传 + 进度条
│   │   │   ├── CachedModels.tsx      # 历史模型列表
│   │   │   ├── ModelViewer.tsx       # 中栏：真实几何 GLB 渲染
│   │   │   ├── BBoxViewer.tsx        # 右栏：分组着色包围盒骨架
│   │   │   ├── TreeView.tsx          # 装配树（多选/搜索/重命名）
│   │   │   └── NodeInspector.tsx     # 节点详情面板
│   │   ├── store/viewerStore.ts      # Zustand 状态管理
│   │   ├── services/api.ts           # API 客户端
│   │   ├── types/model.ts            # TypeScript 类型定义
│   │   └── styles/index.css          # 全局样式
│   └── index.html
├── scripts/                          # Python 脚本
│   ├── dev.mjs                       # 一键启动（前后端）
│   ├── step_to_mesh.py               # STEP → GLB（OCP 曲面细分）
│   └── step_to_bbox.py               # STEP → per-part AABB JSON
├── docs/                             # 文档（本目录）
├── samples/                          # 测试样例
└── .github/                          # CI 配置
```

## 2. 核心数据流

### 2.1 上传 + 解析流程

```
用户拖入 .stp 文件
    │
    ▼
POST /api/models/upload
    │
    ├─ 1. StepParser: 文本流式解析 STEP DATA 段
    │     └ 跟踪括号深度，处理 Creo 多行实体 + 复合实体
    │     └ 输出: Map<id, StepEntity> (类型化实体表)
    │
    ├─ 2. AssemblyTreeBuilder: 构建装配树
    │     └ 解析 NAUO (Next_Assembly_Usage_Occurrence) 父子关系
    │     └ 解析 IDT (Item_Defined_Transformation) 实例变换
    │     └ 解析 CDSR → 复合实体 → RRWT 链（Creo 的变换隐藏在此）
    │     └ 输出: List<AssemblyNode> (带 transform 的层级树)
    │
    ├─ 3. BboxIndexer: 快速 AABB 索引（文本解析 fallback）
    │     └ 单遍 DFS 每个产品的 shape representation
    │     └ 输出: Map<productId, BoundingBox>
    │
    ├─ 4. OCCT bbox 计算 (调用 Python step_to_bbox.py)
    │     └ OCP 加载 STEP → BRepMesh 细分 → 遍历 solids+shells
    │     └ 输出: per-part AABB JSON (权威几何数据)
    │     └ 进度通过 PROGRESS: N/M 行实时上报
    │
    └─ 5. 缓存到磁盘 (.stp + bbox JSON)
         └ 后续加载跳过 OCCT（秒级）
```

### 2.2 渲染流程

```
GET /api/models/{id}/tree
    │
    ├─ 序列化装配树 → JSON TreeNode
    ├─ 每个叶子节点从 OCCT bbox 列表中按序分配 AABB
    └─ 返回前端

GET /api/models/{id}/mesh (首次慢，后续缓存)
    │
    ├─ 调用 step_to_mesh.py (OCP 细分 → trimesh → GLB)
    ├─ 缓存 GLB 到磁盘
    └─ 返回 GLB (Content-Type: model/gltf-binary)
```

## 3. 关键设计决策

### 3.1 为什么用纯 Java 文本解析 + OCCT 双引擎？

- **Java 文本解析器**（StepParser）：快速（4 秒解析 173MB），提取装配结构、命名、变换。但无法可靠提取几何点（Creo 的 CDSR 链太复杂）。
- **OCCT**（Python OCP）：权威几何理解，能正确加载任何 STEP 并细分曲面。但慢（3-10 分钟）且需要 Python 环境。
- **策略**：文本解析用于装配树和命名（快速），OCCT 用于包围盒和网格（准确）。两者互补。

### 3.2 Creo STEP 的三个装配链

Creo 导出的 STEP（CONFIG_CONTROL_DESIGN schema）有三层装配信息：

| 链 | 实体 | 作用 | 我们如何用 |
|---|---|---|---|
| NAUO | NEXT_ASSEMBLY_USAGE_OCCURRENCE | 定义父子关系（BOM） | 装配树结构 |
| IDT | ITEM_DEFINED_TRANSFORMATION | 定义实例位置（平移+旋转） | 部分实例变换（17/3673） |
| CDSR→RRWT | CONTEXT_DEPENDENT_SHAPE_REPRESENTATION → 复合实体 | 定义几何继承 + 大多数实例变换 | CDSR→IDT 链（3673/3673） |

**Creo 的坑**：
1. NAUO 参数顺序是 `(parent, child)` 而非 ISO 标准的 `(child, parent)`
2. 大多数实例变换不在 NAUO 里，在 CDSR 引用的复合实体 `#746=(REPRESENTATION_RELATIONSHIP(...)REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(#741)...)` 里
3. 复合实体的格式 `#id=(TYPE1(...)TYPE2(...))` 不是标准实体，解析器需要特殊处理

### 3.3 包围盒的两阶段计算

| 阶段 | 方法 | 速度 | 准确度 |
|---|---|---|---|
| 上传时 | OCCT 曲面细分 | 3-10 分钟 | 100%（权威） |
| 加载缓存时 | 读 JSON | 2 秒 | 100%（同上） |

文本解析的 BboxIndexer 仅作为 fallback（当 OCCT 环境不可用时）。

### 3.4 动态端口

后端用 `server.port=0` 让 OS 分配空闲端口，写入 `.port` 文件。前端从该文件读取，避免与其他软件冲突。停止时通过 `/actuator/shutdown` 优雅释放。

## 4. REST API

| 方法 | 路径 | 功能 |
|---|---|---|
| POST | `/api/models/upload` | 上传 .stp，解析装配树 + OCCT bbox |
| GET | `/api/models/cached` | 列出已缓存的模型 |
| POST | `/api/models/cached/{id}/load` | 秒级加载已缓存模型 |
| GET | `/api/models/{id}/tree` | 装配树 JSON（含每个叶子 AABB） |
| GET | `/api/models/{id}/metadata` | 模型元数据 |
| GET | `/api/models/{id}/bbox` | 扁平 AABB 列表（JSON/CSV 导出） |
| GET | `/api/models/{id}/mesh` | GLB 网格（首次慢，缓存秒级） |
| GET | `/api/models/{id}/progress` | 解析进度（轮询） |
| PATCH | `/api/models/{id}/nodes/{nodeId}/rename` | 重命名节点（持久化） |
| POST | `/api/models/{id}/merge-groups` | 合并选中零件的 AABB |
| DELETE | `/api/models/{id}/merge-groups/{groupId}` | 删除合并组 |
| POST | `/api/models/{id}/export/step` | 导出骨架 STEP（AP214 长方体） |
| DELETE | `/api/cache?maxAgeHours=24` | 清理旧缓存 |
| POST | `/actuator/shutdown` | 优雅停止后端 |

## 5. 已知限制

1. **mesh 生成内存密集**：大文件（173MB）生成 GLB 时峰值 ~11GB 内存
2. **OCCT bbox 按顺序分配**：OCCT 不保留产品名，per-part AABB 按遍历顺序分配给树的叶子节点（不是精确对应）
3. **GLB 文件大**：整机 GLB ~148MB，浏览器加载需几秒到几十秒
