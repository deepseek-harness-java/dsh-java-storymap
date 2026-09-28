package cn.xiaofuge.dsh.com.dsh.storymap.service;

import cn.xiaofuge.dsh.com.dsh.storymap.config.StoryMapperProperties;
import cn.xiaofuge.dsh.com.dsh.storymap.model.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Story Mapper 用户旅程画板：
 * - sm_ingest_journey：旅程原始记录入库（访谈/反馈/观察笔记）
 * - sm_stage_split：旅程阶段拆分（认知→考虑→购买→使用→续费/推荐）
 * - sm_painpoint_scan：痛点/机会点扫描（情绪词 + 阻塞词识别）
 * - sm_emotion_curve：情绪曲线（每阶段情绪分 -2..+2）
 * - sm_export_md：导出 markdown 画板
 * 红线：不替代用户研究结论——输出只是整理与假设，标注"需真实用户验证"。
 */
@Service
public class StoryMapperService {

    private final StoryMapperProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> journeys = new LinkedHashMap<>();

    public StoryMapperService(StoryMapperProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        Path file = Path.of(properties.dataDirectory(), "journeys.json");
        if (Files.exists(file)) {
            try {
                Map<String, List<Map<String, Object>>> data = objectMapper.readValue(file.toFile(), Map.class);
                for (Map<String, Object> j : data.getOrDefault("journeys", List.of())) {
                    journeys.put(String.valueOf(j.get("id")), j);
                }
            } catch (IOException e) {
                throw new IllegalStateException("无法读取旅程数据", e);
            }
        }
        if (journeys.isEmpty()) {
            seedDemoJourney();
        }
    }

    private void seedDemoJourney() {
        String demo = """
                访谈对象：老张（外卖高频用户）
                想点外卖时先刷短视频看推荐，看到探店视频会心动（认知）。
                打开 App 比价，看评分和配送费，犹豫很久（考虑）。
                下单时发现优惠券不能叠加，有点烦（下单）。
                等餐时反复看骑手位置，越等越饿（配送）。
                餐到了凉了一半，投诉了但赔偿很少（收餐），之后一周没再打开 App。
                """;
        ingest("老张-外卖旅程", "访谈", demo);
    }

    // ---------- 1. 入库 ----------

    public synchronized Map<String, Object> ingest(String name, String sourceType, String content) {
        if (content == null || content.trim().length() < 40) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "旅程记录太短（至少 40 字符）");
        }
        String id = "journey-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("id", id);
        j.put("name", name == null || name.isBlank() ? "旅程-" + id.substring(7) : name);
        j.put("sourceType", sourceType == null || sourceType.isBlank() ? "interview" : sourceType);
        j.put("content", content.trim());
        j.put("ingestedAt", Instant.now().toString());
        j.put("note", "原始记录未经加工，阶段/痛点分析请用 sm_stage_split / sm_painpoint_scan（结果为假设，需真实用户验证）");
        journeys.put(id, j);
        persist();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journeyId", id);
        result.put("name", j.get("name"));
        result.put("totalJourneys", journeys.size());
        return result;
    }

    // ---------- 2. 阶段拆分 ----------

    private static final List<String> STAGE_KEYS = List.of("认知", "考虑", "下单", "配送", "收餐", "使用", "续费", "推荐", "流失");

    public synchronized Map<String, Object> stageSplit(String journeyId) {
        Map<String, Object> j = journeys.get(journeyId);
        if (j == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "旅程不存在: " + journeyId);
        }
        String content = String.valueOf(j.get("content"));
        List<Map<String, Object>> stages = new ArrayList<>();
        Map<String, List<String>> stageEvidence = new LinkedHashMap<>();
        for (String line : content.split("\\n+")) {
            String t = line.trim();
            if (t.isBlank()) continue;
            for (String key : STAGE_KEYS) {
                if (t.contains(key)) {
                    stageEvidence.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
                    break;
                }
            }
        }
        int seq = 1;
        for (var kv : stageEvidence.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("order", seq++);
            s.put("stage", kv.getKey());
            s.put("evidence", kv.getValue());
            s.put("touchpointGuess", guessTouchpoint(kv.getKey()));
            stages.add(s);
        }
        if (stages.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "未能从记录中识别出阶段——记录需包含阶段关键词（" + String.join("/", STAGE_KEYS) + "）。红线：不替代用户研究，请补充真实访谈原文");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journeyId", journeyId);
        result.put("journeyName", j.get("name"));
        result.put("stageCount", stages.size());
        result.put("stages", stages);
        result.put("note", "阶段划分基于记录中的阶段关键词，是初步假设，需与真实用户旅程核对");
        return result;
    }

    private String guessTouchpoint(String stage) {
        return switch (stage) {
            case "认知" -> "短视频/广告/朋友推荐";
            case "考虑" -> "App 比价页/评分评论";
            case "下单" -> "购物车/结算页/优惠券";
            case "配送" -> "骑手轨迹页/通知推送";
            case "收餐" -> "餐品质量/客服赔偿";
            case "使用" -> "产品核心功能";
            case "续费" -> "账单提醒/会员页";
            case "推荐" -> "分享裂变/评价页";
            case "流失" -> "召回推送/竞品";
            default -> "待定";
        };
    }

    // ---------- 3. 痛点/机会点 ----------

    private static final List<String> NEGATIVE_WORDS = List.of("烦", "凉", "投诉", "赔偿", "犹豫", "等", "饿", "贵", "慢", "卡", "失望", "不满", "问题", "错误", "失败", "没");
    private static final List<String> POSITIVE_WORDS = List.of("心动", "满意", "喜欢", "推荐", "方便", "快", "好用", "惊喜", "划算");

    public synchronized Map<String, Object> painpointScan(String journeyId) {
        Map<String, Object> j = journeys.get(journeyId);
        if (j == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "旅程不存在: " + journeyId);
        }
        String content = String.valueOf(j.get("content"));
        List<Map<String, Object>> pains = new ArrayList<>();
        List<Map<String, Object>> opportunities = new ArrayList<>();
        for (String line : content.split("\\n+")) {
            String t = line.trim();
            if (t.isBlank()) continue;
            long neg = NEGATIVE_WORDS.stream().filter(t::contains).count();
            long pos = POSITIVE_WORDS.stream().filter(t::contains).count();
            if (neg >= 1 && neg >= pos) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("quote", t);
                p.put("negativeHits", neg);
                p.put("type", "painpoint");
                p.put("hypothesis", "疑似痛点：情绪词 " + neg + " 个，需访谈确认根因");
                pains.add(p);
            } else if (pos >= 1) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("quote", t);
                o.put("positiveHits", pos);
                o.put("type", "opportunity");
                o.put("hypothesis", "正向体验点：可放大为增长机会");
                opportunities.add(o);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journeyId", journeyId);
        result.put("painpointCount", pains.size());
        result.put("opportunityCount", opportunities.size());
        result.put("painpoints", pains);
        result.put("opportunities", opportunities);
        result.put("note", "痛点/机会点由情绪词规则扫描得出，均为假设——优先级排序请结合真实用户访谈与数据");
        return result;
    }

    // ---------- 4. 情绪曲线 ----------

    public synchronized Map<String, Object> emotionCurve(String journeyId) {
        Map<String, Object> split = stageSplit(journeyId);
        List<Map<String, Object>> curve = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) split.get("stages")) {
            String evidence = String.join(" ", (List<String>) s.get("evidence"));
            long neg = NEGATIVE_WORDS.stream().filter(evidence::contains).count();
            long pos = POSITIVE_WORDS.stream().filter(evidence::contains).count();
            int score = (int) Math.max(-2, Math.min(2, pos - neg));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("stage", s.get("stage"));
            point.put("emotionScore", score);
            point.put("label", score >= 1 ? "偏正向" : score == 0 ? "中性" : score == -1 ? "偏负向" : "明显负向");
            curve.add(point);
        }
        int min = curve.stream().mapToInt(c -> ((Number) c.get("emotionScore")).intValue()).min().orElse(0);
        List<String> valleys = curve.stream()
                .filter(c -> ((Number) c.get("emotionScore")).intValue() == min)
                .map(c -> String.valueOf(c.get("stage"))).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journeyId", journeyId);
        result.put("curve", curve);
        result.put("lowestScore", min);
        result.put("valleyStages", valleys);
        result.put("note", "情绪分为关键词规则估算（-2..+2），仅用于快速定位低谷，最终结论需用户研究支撑");
        return result;
    }

    // ---------- 5. 导出 markdown ----------

    public synchronized Map<String, Object> exportMd(String journeyId) {
        Map<String, Object> j = journeys.get(journeyId);
        if (j == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "旅程不存在: " + journeyId);
        }
        Map<String, Object> split = stageSplit(journeyId);
        Map<String, Object> pains = painpointScan(journeyId);
        Map<String, Object> curve = emotionCurve(journeyId);

        StringBuilder md = new StringBuilder();
        md.append("# 用户旅程画板：").append(j.get("name")).append("\n\n");
        md.append("> 来源类型：").append(j.get("sourceType"))
                .append(" ｜ 生成时间：").append(Instant.now().toString().substring(0, 10))
                .append(" ｜ ⚠️ 以下为规则整理的假设，不替代真实用户研究结论\n\n");
        md.append("## 原始记录\n\n```\n").append(j.get("content")).append("\n```\n\n");
        md.append("## 旅程阶段\n\n");
        md.append("| # | 阶段 | 触点（猜测） | 证据原文 |\n|---|------|------------|---------|\n");
        for (Map<String, Object> s : (List<Map<String, Object>>) split.get("stages")) {
            md.append("| ").append(s.get("order")).append(" | ").append(s.get("stage"))
                    .append(" | ").append(s.get("touchpointGuess"))
                    .append(" | ").append(String.join("；", (List<String>) s.get("evidence"))).append(" |\n");
        }
        md.append("\n## 情绪曲线\n\n");
        for (Map<String, Object> c : (List<Map<String, Object>>) curve.get("curve")) {
            int score = ((Number) c.get("emotionScore")).intValue();
            md.append("- **").append(c.get("stage")).append("**：").append("■".repeat(Math.max(0, score + 3)))
                    .append("（").append(score).append(" ").append(c.get("label")).append("）\n");
        }
        md.append("\n低谷阶段：").append(curve.get("valleyStages")).append("\n\n");
        md.append("## 痛点（假设）\n\n");
        for (Map<String, Object> p : (List<Map<String, Object>>) pains.get("painpoints")) {
            md.append("- ").append(p.get("quote")).append("\n");
        }
        md.append("\n## 机会点（假设）\n\n");
        for (Map<String, Object> o : (List<Map<String, Object>>) pains.get("opportunities")) {
            md.append("- ").append(o.get("quote")).append("\n");
        }
        md.append("\n---\n*由 Story Mapper 规则引擎生成，痛点优先级与根因请结合真实用户访谈验证。*\n");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journeyId", journeyId);
        result.put("journeyName", j.get("name"));
        result.put("markdown", md.toString());
        return result;
    }

    // ---------- 查询 ----------

    public List<Map<String, Object>> listJourneys() {
        return journeys.values().stream().map(j -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.get("id"));
            v.put("name", j.get("name"));
            v.put("sourceType", j.get("sourceType"));
            v.put("charCount", String.valueOf(j.get("content")).length());
            v.put("ingestedAt", j.get("ingestedAt"));
            return v;
        }).collect(Collectors.toList());
    }

    private void persist() {
        try {
            Path file = Path.of(properties.dataDirectory(), "journeys.json");
            Files.createDirectories(file.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("journeys", journeys.values());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存旅程数据", e);
        }
    }
}
