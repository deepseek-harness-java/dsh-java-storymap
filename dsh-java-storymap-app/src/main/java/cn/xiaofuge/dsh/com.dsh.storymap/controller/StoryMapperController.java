package cn.xiaofuge.dsh.com.dsh.storymap.controller;

import cn.xiaofuge.dsh.com.dsh.storymap.config.StoryMapperProperties;
import cn.xiaofuge.dsh.com.dsh.storymap.model.ApiException;
import cn.xiaofuge.dsh.com.dsh.storymap.service.StoryMapperService;
import cn.xiaofuge.dsh.com.dsh.storymap.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class StoryMapperController {
    private final StoryMapperService storyService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final StoryMapperProperties properties;

    public StoryMapperController(StoryMapperService storyService,
                                 HarnessAiStreamService harnessAiStreamService,
                                 StoryMapperProperties properties) {
        this.storyService = storyService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record IngestRequest(String name, String sourceType, String content) {
    }

    public record JourneyRequest(String journeyId) {
    }

    @GetMapping("/api/story/journeys")
    public List<Map<String, Object>> journeys() {
        return storyService.listJourneys();
    }

    @PostMapping("/api/story/ingest")
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        return storyService.ingest(request.name(), request.sourceType(), request.content());
    }

    @PostMapping("/api/story/stages")
    public Map<String, Object> stages(@RequestBody JourneyRequest request) {
        return storyService.stageSplit(request.journeyId());
    }

    @PostMapping("/api/story/painpoints")
    public Map<String, Object> painpoints(@RequestBody JourneyRequest request) {
        return storyService.painpointScan(request.journeyId());
    }

    @PostMapping("/api/story/emotion")
    public Map<String, Object> emotion(@RequestBody JourneyRequest request) {
        return storyService.emotionCurve(request.journeyId());
    }

    @PostMapping("/api/story/export")
    public Map<String, Object> export(@RequestBody JourneyRequest request) {
        return storyService.exportMd(request.journeyId());
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Story Mapper（用户旅程画板）：旅程记录入库、阶段拆分、痛点/机会点扫描、情绪曲线、markdown 导出\n");
        prompt.append("- 你可以使用以下工具（分析前必须先调用工具查证，禁止臆造旅程数据）：\n");
        prompt.append("  - plugin__dsh-java-storymap-plugin__sm_ingest_journey(name, sourceType, content)：旅程原始记录入库\n");
        prompt.append("  - plugin__dsh-java-storymap-plugin__sm_stage_split(journeyId)：旅程阶段拆分（认知→考虑→下单→配送→收餐…）\n");
        prompt.append("  - plugin__dsh-java-storymap-plugin__sm_painpoint_scan(journeyId)：痛点/机会点扫描\n");
        prompt.append("  - plugin__dsh-java-storymap-plugin__sm_emotion_curve(journeyId)：情绪曲线（-2..+2）\n");
        prompt.append("  - plugin__dsh-java-storymap-plugin__sm_export_md(journeyId)：导出 markdown 画板\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 阶段/痛点/情绪结论只引用工具返回的真实证据原文。\n");
        prompt.append("- 红线：不替代用户研究结论——所有痛点与机会点都标注为假设，建议用户做真实访谈验证后再定优先级。\n");
        return prompt.toString();
    }
}
