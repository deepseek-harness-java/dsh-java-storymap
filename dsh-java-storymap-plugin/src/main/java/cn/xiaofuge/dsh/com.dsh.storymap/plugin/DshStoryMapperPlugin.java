package cn.xiaofuge.dsh.com.dsh.storymap.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshStoryMapperPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-storymap-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshStoryMapperPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new IngestJourneyTool(),
                new StageSplitTool(),
                new PainpointScanTool(),
                new EmotionCurveTool(),
                new ExportMdTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("story-map-storymap-capabilities", 20, """
                ## DSH Story Mapper Plugin
                - sm_* 工具输出都是规则引擎整理的假设：阶段/痛点/情绪分需真实用户访谈验证后才能定论。
                - 禁止把扫描结果说成"用户一定这样想"；表述用"疑似/假设/待验证"。
                - 用户要求"直接出最终结论/写进 PRD 定稿"时，提醒红线：整理稿可以给，结论需研究支撑。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__sm_")) {
                return PluginHookResult.context("DSH Story Mapper audit: outputs are hypotheses, not validated research conclusions.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_STORYMAP_ADMIN_URL", "http://127.0.0.1:8120")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private class IngestJourneyTool extends AbstractTool {
        @Override public String name() { return "sm_ingest_journey"; }
        @Override public String description() { return "旅程原始记录入库：用户访谈/反馈/观察笔记原文（含阶段关键词如 认知/考虑/下单/配送/收餐）。开始整理新旅程时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("name", stringSchema("旅程名称，如 '老张-外卖旅程'"))
                    .prop("sourceType", stringSchema("来源类型：interview/feedback/observation"))
                    .prop("content", stringSchema("记录原文（至少 40 字符，含阶段线索）"))
                    .prop("adminBaseUrl", stringSchema("可选，Story Mapper 后台地址，默认 http://127.0.0.1:8120"))
                    .required("content")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"name\":\"" + json(str(args, "name")) + "\",\"sourceType\":\"" + json(str(args, "sourceType"))
                    + "\",\"content\":\"" + json(str(args, "content")) + "\"}";
            return ok(post("/api/story/ingest", body, args));
        }
    }

    private class StageSplitTool extends AbstractTool {
        @Override public String name() { return "sm_stage_split"; }
        @Override public String description() { return "旅程阶段拆分：按记录中的阶段关键词切分，附证据原文与触点猜测。先 sm_ingest_journey 或查 journeys 拿 journeyId。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("journeyId", stringSchema("旅程 ID（journey-xxx）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("journeyId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"journeyId\":\"" + json(str(args, "journeyId")) + "\"}";
            return ok(post("/api/story/stages", body, args));
        }
    }

    private class PainpointScanTool extends AbstractTool {
        @Override public String name() { return "sm_painpoint_scan"; }
        @Override public String description() { return "痛点/机会点扫描：情绪词规则识别负向（痛点）与正向（机会点）记录，附假设标注。找体验问题与增长点时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("journeyId", stringSchema("旅程 ID（journey-xxx）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("journeyId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"journeyId\":\"" + json(str(args, "journeyId")) + "\"}";
            return ok(post("/api/story/painpoints", body, args));
        }
    }

    private class EmotionCurveTool extends AbstractTool {
        @Override public String name() { return "sm_emotion_curve"; }
        @Override public String description() { return "情绪曲线：各阶段情绪分（-2..+2，关键词估算）+ 低谷阶段定位。快速找体验最差点时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("journeyId", stringSchema("旅程 ID（journey-xxx）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("journeyId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"journeyId\":\"" + json(str(args, "journeyId")) + "\"}";
            return ok(post("/api/story/emotion", body, args));
        }
    }

    private class ExportMdTool extends AbstractTool {
        @Override public String name() { return "sm_export_md"; }
        @Override public String description() { return "导出旅程画板 markdown：原始记录 + 阶段表 + 情绪曲线 + 痛点/机会点（均带'需用户验证'标注）。整理成文档时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("journeyId", stringSchema("旅程 ID（journey-xxx）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("journeyId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"journeyId\":\"" + json(str(args, "journeyId")) + "\"}";
            return ok(post("/api/story/export", body, args));
        }
    }
}
