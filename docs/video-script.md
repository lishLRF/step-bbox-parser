# 视频讲解设计稿

> 本文档为录制教学/演示视频的参考脚本，涵盖软件设计思路、技术难点、核心代码解读。

## 视频大纲

### 第一部分：项目背景与目标（2 分钟）

**要点**：
- 机械工程师拿到 STEP 装配体文件，想在不装重型 CAD 软件的前提下快速看清整机结构
- 核心需求：装配树 + 每个零件的包围盒 + 真实几何外形
- 示例模型：GMC2550WRS 龙门机床（173MB，3598 个零件）

### 第二部分：技术架构总览（3 分钟）

**三栏界面演示**：
- 左栏：装配树（可多选、搜索、重命名）
- 中栏：整机真实几何（OCCT 渲染的 GLB）
- 右栏：分组着色包围盒骨架

**技术栈**：
```
Java 21 + Spring Boot（后端）
  ↕ REST API
React + Three.js（前端）
  ↕ subprocess
Python + OCP/OCCT（几何引擎）
```

**为什么双引擎**：
- Java 文本解析器：快速提取装配结构和命名（4 秒/173MB）
- OCCT Python：权威几何理解，计算包围盒和网格（3-10 分钟）
- 两者互补：文本解析用于树和名字，OCCT 用于几何

### 第三部分：STEP 格式解析难点（5 分钟）

**STEP 文件长什么样**：
```
ISO-10303-21;
DATA;
#44=CARTESIAN_POINT('',(0.E0,0.E0,0.E0)));
#47=AXIS2_PLACEMENT_3D('GMC2550WRS\X2\4E3B673A\X0\',#44,...);
```

**三个坑**：

1. **Creo 的多行实体**：几乎每个参数单独一行，必须跟踪括号深度
2. **`\X2\` 中文转义**：`GMC2550WRS\X2\4E3B673A\X0\` → `GMC2550WRS主机`
3. **复合实体**：`#746=(REPRESENTATION_RELATIONSHIP(...)REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(#741)...)` — 实例变换藏在这里

**代码解读**：`StepParser.java` 的括号深度追踪 + 复合实体识别

### 第四部分：装配树构建（5 分钟）

**Creo 的三层装配链**：
```
NAUO → 定义"装配 A 用了零件 B"（父子关系）
  ↓
IDT → 定义"零件 B 放在装配 A 的什么位置"（变换矩阵）
  ↓
CDSR → 定义几何继承（复合实体里的 RRWT 引用 IDT）
```

**关键发现**：
- NAUO 参数顺序是 `(parent, child)`（和 ISO 标准相反！）
- 3673 个实例变换中，只有 17 个在 NAUO 里直接携带，其余 3656 个藏在 CDSR→复合实体→RRWT 链中

**代码解读**：`AssemblyTreeBuilder.java` 的 `indexNauoToIdt` 方法

### 第五部分：包围盒计算（5 分钟）

**为什么不用文本解析算包围盒**：
- Creo 的几何继承通过 CDSR 链传递，文本 DFS 无法可靠到达所有几何点
- 测试：文本解析只有 3552/3598 非零包围盒，且大量扁平

**OCCT 方案**：
- `step_to_bbox.py` 用 OCP 加载 STEP
- `BRepMesh_IncrementalMesh` 细分所有曲面
- 遍历 solids + shells（去重），每个计算 AABB
- 结果：3598/3599 非零，完整三维（X=6.4m, Y=6.8m, Z=10.9m）

**性能优化**：
- 上传时一次计算，缓存 JSON
- 加载缓存秒级（跳过 OCCT）

### 第六部分：真实几何渲染（3 分钟）

**STEP → GLB 管线**：
- `step_to_mesh.py` 用 OCP 加载 STEP
- 遍历每个 face 的 Poly_Triangulation
- 合并成单个 trimesh.Trimesh
- 导出 GLB（Three.js 原生支持）

**前端渲染**：
- drei 的 `useGLTF` 加载 GLB
- 相机自动适配模型包围球（near/far 随尺寸缩放）

### 第七部分：动态端口与缓存（2 分钟）

- `server.port=0` → OS 自动选端口
- `.port` 文件 → 前端发现后端
- `/actuator/shutdown` → 优雅停止
- 缓存在 `Z:/Project/3Dbox-step/cache/`

### 第八部分：演示（5 分钟）

1. 上传 GMC2550WRS（等进度条到 100%）
2. 点击历史模型秒级加载
3. 三栏效果展示
4. 多选零件 + 合并
5. 导出骨架 STEP
6. 停止（Ctrl+C）

## 录制建议

- 先跑通完整流程再录
- 大文件解析提前缓存好（录制时用历史模型加载）
- 录制时用小样例 `multi-asm.stp` 演示快速效果
- 用 GMC2550WRS 演示大模型效果（提前缓存）
