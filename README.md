# DSH Story Mapper (dsh-java-storymap)

用户旅程画板（Story Mapper）——一个运行在 DeepSeek Harness 之上的独立 AI 产品。AI 产品经理/用户体验分析师把访谈、反馈、观察笔记录入后，由 Java 原生插件工具完成**阶段拆分、痛点/机会点扫描、情绪曲线、Markdown 画板导出**，所有结论均标注为假设，不替代真实用户研究。

> 红线：输出只是整理与假设——痛点优先级与根因必须经真实用户访谈验证；不把规则扫描结果当定论对外发布。

## 架构

```
┌────────────────────────────┐        ┌──────────────────────────┐
│  dsh-java-storymap-app     │  SSE   │  DeepSeek Harness        │
│  Spring Boot 3 (:8120)     │──────▶│  127.0.0.1:8090          │
│  - 旅程 CRUD REST API      │        │  - LLM Agent 编排        │
│  - AI 对话（转发 harness）  │◀──────│  - Java Native Plugin    │
│  - 旅程画板 Web UI         │  tool  │    dsh-java-storymap-    │
└────────────────────────────┘ calls  │    plugin（5 个工具）     │
                                      └──────────────────────────┘
```

- **app**（端口 8120）：REST API + Web UI + SSE AI 对话（转发 harness `/api/agent/stream`）
- **plugin**（Java Native）：5 个只读/入库工具，以 `plugin__dsh-java-storymap-plugin__<tool>` 暴露给 AI

## 5 个插件工具

| 工具 | 作用 |
|------|------|
| `sm_ingest_journey(name, sourceType, content)` | 旅程原始记录入库（访谈/反馈/观察笔记，≥40 字符） |
| `sm_stage_split(journeyId)` | 阶段拆分：认知→考虑→下单→配送→收餐→使用→续费→推荐→流失，附触点猜测与证据原文 |
| `sm_painpoint_scan(journeyId)` | 痛点/机会点扫描：负向/正向情绪词规则识别，每条附假设标注 |
| `sm_emotion_curve(journeyId)` | 情绪曲线：每阶段 -2..+2 分，自动定位低谷阶段 |
| `sm_export_md(journeyId)` | 导出 Markdown 画板（阶段表 + 情绪曲线 + 痛点/机会点，带假设声明） |

## Web UI

- **旅程面板**：已录入旅程列表（名称/来源/字数/日期），一键选为当前旅程
- **画板面板**：当前旅程的阶段/情绪/痛点可视化
- **AI 对话**：让 AI 拆分阶段、扫描痛点、画情绪曲线、导出画板
- 录入弹窗：快速录入访谈/反馈原文

## REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/story/journeys` | 旅程列表 |
| POST | `/api/story/ingest` | 录入旅程 `{name, sourceType, content}` |
| POST | `/api/story/stages` | 阶段拆分 `{journeyId}` |
| POST | `/api/story/painpoints` | 痛点扫描 `{journeyId}` |
| POST | `/api/story/emotion` | 情绪曲线 `{journeyId}` |
| POST | `/api/story/export` | 导出 markdown `{journeyId}` |
| POST | `/api/assistant/stream` | SSE AI 对话 `{agentId, message}` |

## 快速开始

```bash
# 1. 构建
mvn package -DskipTests

# 2. 启动 app（确保 harness 已在 127.0.0.1:8090 运行）
SERVER__PORT=8120 java -jar dsh-java-storymap-app/target/dsh-java-storymap-app-0.1.0-SNAPSHOT.jar --server.port=8120

# 3. 安装插件到 harness
curl -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' -d '{
  "pluginId": "dsh-java-storymap-plugin",
  "jarName": "dsh-java-storymap-plugin-0.1.0-SNAPSHOT.jar",
  "displayName": "Story Mapper",
  "pluginVersion": "0.1.0",
  "runtimeType": "JAVA_NATIVE",
  "sourcePath": "'$PWD'/dsh-java-storymap-plugin/target/dsh-java-storymap-plugin-0.1.0-SNAPSHOT.jar",
  "entrypoint": "cn.xiaofuge.dsh.com.dsh.storymap.plugin.DshStoryMapperPlugin",
  "installMode": "REPLACE"
}'
curl -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' -d '{"pluginId":"dsh-java-storymap-plugin"}'

# 4. 打开 UI
open http://127.0.0.1:8120
```

## E2E 测试

```bash
NO_PROXY='*' python3 story_map_e2e.py
```

36 项断言覆盖：种子旅程、入库校验（短文本 400）、阶段拆分（关键词识别/幂等/404/无阶段 422 红线提示）、痛点扫描（真实证据/假设标注）、情绪曲线（-2..+2 范围/低谷/标签）、Markdown 导出（表格/假设声明）、SSE AI 全链路（点名调用插件工具、拒绝 shell 绕路、拒绝把假设当定论对外发布）。

## 截图

| 文件 | 内容 |
|------|------|
| `screenshots/01-home.png` | 旅程画板首页：3 条真实旅程（老张-外卖/小李-健身课/小王-退货） |
| `screenshots/02-ai-drawer.png` | AI 真实调用 `sm_stage_split` + `sm_emotion_curve`：阶段拆分 + 情绪低谷分析，结论标注假设 |
| `screenshots/03-safety.png` | AI 拒绝把未验证的痛点扫描当定论写对外公告，给出"带假设标注的内部草稿 + 访谈问题 + 数据报告"替代方案 |

## 数据

旅程数据持久化在 `~/.dsh-java-storymap/journeys.json`（首次启动自动写入种子旅程"老张-外卖旅程"）。
