# PRD — STEP 装配树 + 包围盒解析查看器

> Status: Ready for agent · Owner: TBD · Created: 2026-07-04
> Source sample: `gmc2550wrs-0001.stp` (Creo Parametric, ISO-10303-21,
> schema `CONFIG_CONTROL_DESIGN` + `SHAPE_APPEARANCE_LAYERS_GROUPS`,
> ~100 MB, hundreds of parts).

---

## Problem Statement

机械工程师拿到一个由 SolidWorks / Creo / NX / CATIA 导出的 `.stp` 装配体文件时，
想在**不打开重型 CAD 软件**的前提下，快速看清这台机器的**装配结构**（有哪些子装配、
哪些零件、它们的层级关系）以及每个零件在整机里的**空间占位**——也就是一个能完全包住
该零件、且相对位置正确的**长方体包围盒**。

现有的做法要么是装一个几 GB 的 CAD 客户端慢慢打开，要么依赖商业轻量化查看器（往往
收费、装插件、对中文/编码支持差）。我们需要一个**零安装、纯浏览器、上传即看**的工具：
上传一个 STEP 文件，几秒到几十秒内，左边看到装配树、中间看到所有零件的包围盒长方体
堆叠成的"骨架模型"。

## Solution

一个前后端分离的 Web 应用：

- **后端**（Java 21 + Spring Boot）：一个**纯 Java 自研**的 STEP (ISO-10303-21)
  解析器，不依赖任何原生 CAD 内核。它读取 `.stp` 的 `DATA` 段，识别装配关系
  （`PRODUCT_DEFINITION` / `NEXT_ASSEMBLY_USAGE_OCCURRENCE`）和几何引用
  （`CARTESIAN_POINT` / 顶点 / B-rep 顶点），按装配实例的变换矩阵
  （`AXIS2_PLACEMENT_3D`）把每个零件的所有点累加到**装配根坐标系**下，取 min/max
  得到该零件在整机坐标系下的轴对齐包围盒（AABB）。
- **前端**（React + Vite + Three.js）：一个 SPA，提供上传、左侧装配树、中间 3D
  视口（把所有 AABB 作为线框长方体渲染出来，形成整机的骨架模型）、节点可在前端
  重命名。

包围盒精度取**顶点云 AABB**（不做精确 B-rep 曲面包络、不做 OBB、不算物理属性、不做
干涉检查、不做鉴权）——这些对"看清装配结构 + 空间占位"的目标已经足够，且能保证纯
Java、零原生依赖的可移植性。

## User Stories

> 编号便于后续拆 issue。Actor 默认为「工程师（终端用户）」。

### 上传与解析

1. 作为工程师，我想通过浏览器拖拽或选择一个 `.stp` / `.step` 文件上传，以便开始解析。
2. 作为工程师，我想在上传过程中看到进度条，以便知道上传没卡住。
3. 作为工程师，我想上传后页面同步等待解析完成（带 loading 态），以便拿到结果就能直接看。
4. 作为工程师，我想在上传了不支持的文件类型时（非 `.stp`/`.step`）立即得到清晰提示，以免白等。
5. 作为工程师，我想在文件超过大小上限时得到友好错误，以便改用小文件或联系管理员。
6. 作为工程师，我想在 STEP 文件解析失败（格式损坏/不支持的 schema）时得到可读错误信息。

### 装配树

7. 作为工程师，我想看到完整的装配树（根装配 → 子装配 → … → 零件），以便理解整机结构。
8. 作为工程师，我想每个树节点同时显示 STEP 内嵌名称和产品编号（若存在），以便和我的 BOM / SolidWorks 零件名对得上。
9. 作为工程师，我想在装配树里展开/折叠任意子树，以便聚焦当前关心的部分。
10. 作为工程师，我想在装配树节点上**重命名**（仅前端、本地保存），以便用我自己熟悉的标签做标注。
11. 作为工程师，我想在树上点击一个节点，3D 视口里对应零件/子装配高亮，以便快速定位。
12. 作为工程师，我想看到每个节点的类型标识（装配 / 子装配 / 零件），以便一眼区分结构层级。
13. 作为工程师，我想在节点上看到该零件的包围盒尺寸（长×宽×高）和中心坐标，以便做空间估算。
14. 作为工程师，我想按名称/编号在树里搜索并过滤节点，以便在大装配里快速找零件。

### 3D 骨架视图

15. 作为工程师，我想在 3D 视口里看到**所有**零件的包围盒以线框长方体形式同时显示，以便一眼看到整机的空间占位（骨架模型）。
16. 作为工程师，我想每个包围盒的长方体位置准确（装配根坐标系下），以便不同零件之间的相对位置和真实装配一致。
17. 作为工程师，我想在 3D 视口里旋转/平移/缩放（orbit/pan/zoom），以便从任意角度查看。
18. 作为工程师，我想点击一个长方体，对应的树节点高亮并显示其属性，以便双向联动。
19. 作为工程师，我想选中某节点时，只高亮它的盒子而其他半透明，以便聚焦。
20. 作为工程师，我想按节点类型或按子装配着色（同一子装配同色），以便区分结构分组。
21. 作为工程师，我想能切换"全部盒子 / 仅选中子树 / 仅叶子零件"三种显示模式。
22. 作为工程师，我想看到坐标轴指示和地面网格，以便判断方向和尺度。
23. 作为工程师，我想看到整机包围盒和单位（mm/inch），以便把握整体尺寸。

### 元数据与导出

24. 作为工程师，我想看到文件的来源 CAD 系统、schema、单位、零件总数、装配数，以便了解文件背景。
25. 作为工程师，我想把当前装配的"零件 → 包围盒"列表导出为 JSON / CSV，以便在别处复用。
26. 作为工程师，我想能把整棵装配树（含重命名）导出为 JSON，以便存档。

### 健壮性/部署

27. 作为运维，我想后端解析在受限线程池 + 超时下运行，以免一个坏文件拖垮服务。
28. 作为运维，我想上传文件存放在 web root 之外、解析完即删，以降低泄露风险。
29. 作为运维，我想有 `health`/`info` 端点，以便接入监控。
30. 作为工程师，我想应用能在 Windows / Linux 上同样部署（纯 Java + 静态前端），以便适配现有环境。

## Implementation Decisions

### 模块划分（后端 `com.cadbbox.parser`）

- **`step`** — ISO-10303-21 解析器。纯文本流式 tokenizer，把 `DATA` 段解析成
  `Map<Integer, StepEntity>`（实体 id → 类型 + 参数列表）。识别的实体类型先覆盖
  AP203/AP214/AP242 中与装配和几何引用相关的子集：`CARTESIAN_POINT`、`DIRECTION`、
  `AXIS2_PLACEMENT_3D`、`VERTEX_POINT`、`MANIFOLD_SOLID_BREP`（仅取其引用的顶点）、
  `PRODUCT_DEFINITION`、`NEXT_ASSEMBLY_USAGE_OCCURRENCE`、`PRODUCT_DEFINITION_FORMATION`、
  `PRODUCT`、`GEOMETRIC_REPRESENTATION_CONTEXT` 等。**不做**真正的 B-rep 曲面求值。
- **`tree`** — 装配树构建器。输入 `ParsedStepFile`，输出 `TreeNode` 层级。每个节点携带
  该实例相对父节点的局部 4×4 变换矩阵（来自 `AXIS2_PLACEMENT_3D`），并提供"把某叶子
  变换链累乘到根"的能力。
- **`bbox`** — 包围盒计算器。对每个叶子零件，收集其引用的所有 `CARTESIAN_POINT`
  （含 B-rep 顶点最终指向的点），逐点应用从叶子到根的变换链，在**装配根坐标系**下取
  min/max 得到 AABB。同时输出 `center` 和 `size` 供渲染。
- **`web`** — REST 控制器 + DTO + 异常处理。是后端**唯一**对外边界。
  - `POST /api/models/upload`（multipart）→ 解析、缓存、返回 `ModelMetadata`（含 `id`）。
  - `GET /api/models/{id}/tree` → 完整 `TreeNode`（每节点含 transform 与 bbox）。
  - `GET /api/models/{id}/bbox` → 扁平 `PartBBox[]`（叶子在根坐标系下的盒子）。
  - `GET /api/models/{id}/metadata`、`DELETE /api/models/{id}`。
  - 错误统一用 RFC 9457 `application/problem+json`。

### 解析策略

- **同步**：上传即解析，HTTP 请求内完成（MVP 阶段）。100 MB 量级文件目标 < 60s。
- 解析在**有界线程池**（可配置，默认 4）+ **硬超时**（默认 120s）下运行。
- 文件校验：扩展名 + 魔数（`ISO-10303-21;` 起始）。
- 实体引用按 id 索引，支持前向/后向引用（STEP 允许先引用后定义）。
- 中文名以 STEP 的 `\X2\....\X0=` UTF-16 转义还原；同时尽量从 `PRODUCT` 实体抽产品编号。

### 坐标与单位

- 所有包围盒一律在**装配根坐标系**下表达（前端零额外变换即可渲染）。
- 单位从 `GEOMETRIC_REPRESENTATION_CONTEXT` / `GLOBAL_UNIT_ASSIGNED_CONTEXT` 读取，
  默认 mm；前端在 UI 上显式标注单位。

### 前端（`frontend/src`）

- **`services/api.ts`** — 唯一与后端通信的出口，类型来自 `types/model.ts`（与后端 DTO 对齐）。
- **`store/`** — Zustand store：当前 model、tree、选中节点、重命名映射、显示模式。
- **`components/`** —
  - `ModelUploader`（拖拽 + 进度）
  - `TreeView`（展开/折叠/搜索/重命名/选中联动）
  - `BBoxViewer`（react-three-fiber 场景：所有 AABB 线框 + 坐标轴 + 网格 + 选中高亮）
  - `NodeInspector`（右侧详情：名称/编号/类型/尺寸/中心/transform）
  - `MetadataBar`（来源 CAD/schema/单位/零件数）
- **`pages/ViewerPage`** — 三栏布局（上传 | 3D | 树+详情）。
- 重命名仅前端、持久化到 localStorage（MVP 不入库）。

### 几个明确"不做"

- 不还原零件的真实几何外形（只画包围盒长方体，这正是"骨架模型"）。
- 不算质量/重心/惯性。
- 不做装配干涉/碰撞检测。
- 不做用户登录/权限/多租户。
- 不做精确 B-rep 曲面包络、不做 OBB。

### 关键类型形状（来自原型，编码决策）

`TreeNode`（前后端共用形态）：

```
TreeNode = {
  id, name, productLabel?,
  type: 'ASSEMBLY' | 'SUBASSEMBLY' | 'PART',
  transform?: { matrix: number[16] },   // 相对父的局部变换
  boundingBox?: { min:Vec3, max:Vec3, center:Vec3, size:Vec3 } | null,
  children: TreeNode[]
}
```

`BoundingBox` 一律为**装配根坐标系下的 AABB**，并附带 `center`/`size` 便于 Three.js
直接以 `BoxGeometry` + 中心定位渲染。

## Testing Decisions

**总原则**：只测外部行为，不测私有实现细节；优先用最高的 seam。

- **Golden-file 测试（最强信号，L2）**
  - 对 `samples/step/*.stp` 的每个文件，运行 `StepParser` + `AssemblyTreeBuilder` +
    `BoundingBoxCalculator`，把输出规范化（JSON key 排序、浮点四舍五入到 6 位）后，
    与 `samples/bbox-output/*.json` 比对，要求字节级一致。
  - 规范化在 `scripts/normalize-json.*` 里，保证 diff 稳定。
  - 新增样例时跑 `scripts/regen-golden-files.*` 重生成，并在 PR 里说明理由。
  - 至少包含：① GMC2550WRS 真实样例（裁剪到可提交体积）；② 一个仅含单零件的最小样例；
    ③ 一个两层级装配含平移+旋转的合成样例；④ 一个含中文 `\X2\` 转义名的样例。

- **领域纯函数单元测试（L2）**
  - `BoundingBoxCalculator`：用手工构造的、已知答案的 `ParsedStepFile`（例如一个
    顶点在 (0,0,0)、(10,0,0)、(0,20,0) 的零件，预期 AABB min=(0,0,0) max=(10,20,0)）。
  - `AssemblyTreeBuilder`：含变换链累乘的合成样例，验证根坐标系下的盒子正确。

- **REST API 契约测试（L1，最高外部 seam）**
  - `@SpringBootTest` + MockMvc：上传一个固定样例 → 调 `/tree` → 对返回 JSON 做
    snapshot 断言（树节点数、根名、若干已知叶子的 bbox）。
  - 错误路径：坏扩展名、超大、损坏文件 → 断言 problem+json 状态码与 type。

- **前端（L3/L4）**
  - Vitest + Testing Library：`TreeView` 给定 mock tree → 断言节点数、搜索过滤、
    重命名回填 store。
  - Playwright E2E（L4）：上传样例 → 树里出现 N 个节点 → 3D 视口里出现 M 个
    `mesh`/`lineSegments` → 点节点→盒子高亮。E2E 不验证像素，只验证 DOM/场景结构。

## Out of Scope

- 零件真实几何外形还原 / 三角网格渲染（只画 AABB 长方体）。
- 精确 B-rep 曲面包络、OBB 有向包围盒。
- 质量 / 重心 / 惯性 / 材料属性。
- 装配干涉 / 碰撞 / 间隙检查。
- 用户认证、权限、多租户、团队协作。
- 服务端持久化（解析结果只在内存/临时目录缓存；前端重命名只存 localStorage）。
- 异步任务队列、SSE 进度推送（MVP 同步即可；预留接口供后续切换）。
- STEP AP242 语义里的 PMI（产品制造信息）、图层、外观材质的完整还原（仅读取必要的
  装配/几何实体）。
- 其他格式（IGES / Parasolid / 原生 SLDPRT / IPT 等）。

## Further Notes

- **样例文件**：`gmc2550wrs-0001.stp` 很大（~100 MB）且含数百零件，**不直接提交进
  仓库**。需要从它裁出一个"中等规模子装配"（例如某个铣头子装配，几十个零件）作为
  可提交的 golden sample；完整大文件用于本地性能回归。
- **STEP schema 多样性**：不同 CAD 导出的 schema 不同（AP203 / AP214 / AP242 /
  `CONFIG_CONTROL_DESIGN`）。解析器按"实体类型名 + 关键参数位置"做容错匹配，不依赖
  固定 schema；遇到未知实体跳过但不报错。
- **性能**：100 MB 文件、几十万实体时，解析器需流式 + 增量索引，避免一次性把整个
  DATA 段读进内存做字符串切分。后续可作为优化点。
- **国际化**：UI 文案默认中文（你的目标用户），但代码与注释用英文，遵循开源惯例。
- **后续可演进项**（不在本期）：异步解析 + 进度、OBB、几何网格渲染、多格式、协作标注。
