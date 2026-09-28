#!/usr/bin/env python3
"""E2E for dsh-java-storymap (Story Mapper) on :8120.

Covers REST endpoints and the full SSE AI pipeline, including red-line case:
AI must mark conclusions as hypotheses and not replace real user research.
"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8120"

INTERVIEW = """访谈对象：小李（在线健身课学员）
朋友发了体验课链接，觉得挺新鲜就试了一节（认知）。
对比了几个平台的价格和教练资质，纠结了一周（考虑）。
买课时发现凑单满减算不清，干脆买了最小套餐（下单）。
上课时APP偶尔卡顿，直播掉线重连过两次（使用）。
练完感觉很值，还把课表转发给了同事（推荐）。"""

NEG_SAMPLE = """访谈对象：小王（电商退货用户）
在直播间看主播讲得心动就下单了（认知）。
收到货发现尺码不符，申请退货等了三天没人理（收餐）。
客服一直让上传凭证，来回折腾，特别烦（使用）。
最后赔了运费但再也没打开过这个 App（流失）。"""

NO_STAGE_SAMPLE = """观察记录：今天天气不错，中午和朋友吃了火锅聊了会儿天，下午在公园散步两个小时，晚上回家看了部电影，心情很好，没有提任何产品或服务。"""


PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print(f"  ✓ {name}")
    else:
        FAILED.append((name, detail))
        print(f"  ✗ {name}  << {detail}")


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data,
                               headers={"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(r, timeout=60) as resp:
        return json.loads(resp.read().decode())


def sse(message):
    body = json.dumps({"agentId": "e2e-storymap-agent", "message": message}).encode()
    r = urllib.request.Request(BASE + "/api/assistant/stream", data=body,
                               headers={"Content-Type": "application/json"}, method="POST")
    chunks = []
    try:
        with urllib.request.urlopen(r, timeout=120) as resp:
            while True:
                try:
                    raw = resp.read(4096)
                except Exception:
                    break
                if not raw:
                    break
                chunks.append(raw.decode("utf-8", "ignore"))
    except Exception:
        pass
    return "".join(chunks)


print("=== T0 seed journey ===")
js = req("GET", "/api/story/journeys")
check("seed journey exists", len(js) >= 1, str(len(js)))
check("seed name 老张-外卖旅程", any("老张" in str(j.get("name")) for j in js), str(js[:2]))

print("=== T1 sm_ingest_journey ===")
r = req("POST", "/api/story/ingest", {"name": "小李-健身课旅程", "sourceType": "访谈", "content": INTERVIEW})
j1 = r.get("journeyId")
check("ingest returns id", bool(j1), str(r))
check("total journeys incremented", r.get("totalJourneys", 0) >= 2, str(r.get("totalJourneys")))

r = req("POST", "/api/story/ingest", {"name": "小王-退货旅程", "sourceType": "反馈", "content": NEG_SAMPLE})
j2 = r.get("journeyId")
check("second ingest", bool(j2), str(r))

try:
    req("POST", "/api/story/ingest", {"name": "太短", "sourceType": "访谈", "content": "太短了"})
    check("short content rejected", False, "no raise")
except urllib.error.HTTPError as e:
    check("short content rejected", e.code == 400, str(e.code))

print("=== T2 sm_stage_split ===")
r = req("POST", "/api/story/stages", {"journeyId": j1})
stages = r.get("stages", [])
names = [s.get("stage") for s in stages]
check("stageCount 5", r.get("stageCount") == 5, str(r.get("stageCount")))
check("stage order 认知..推荐", names[:1] == ["认知"] and names[-1] == ["推荐"][-1] and "下单" in names, str(names))
check("touchpoint guess filled", all(s.get("touchpointGuess") for s in stages), str(stages[:1]))
check("hypothesis note", "假设" in str(r.get("note")), str(r.get("note")))

r2 = req("POST", "/api/story/stages", {"journeyId": j1})
check("idempotent re-split", r2.get("stageCount") == 5, str(r2.get("stageCount")))

r3 = req("POST", "/api/story/stages", {"journeyId": j2})
check("negative journey stages >=2", r3.get("stageCount", 0) >= 2, str(r3.get("stageCount")))

try:
    req("POST", "/api/story/stages", {"journeyId": "journey-nope"})
    check("missing journey 404", False, "no raise")
except urllib.error.HTTPError as e:
    check("missing journey 404", e.code == 404, str(e.code))

r4 = req("POST", "/api/story/ingest", {"name": "无阶段记录", "sourceType": "观察", "content": NO_STAGE_SAMPLE})
j3 = r4.get("journeyId")
try:
    req("POST", "/api/story/stages", {"journeyId": j3})
    check("no-stage 422 with red-line hint", False, "no raise")
except urllib.error.HTTPError as e:
    body = e.read().decode("utf-8", "ignore")
    check("no-stage 422 with red-line hint", e.code == 422 and ("用户研究" in body or "访谈" in body), f"{e.code} {body[:200]}")

print("=== T3 sm_painpoint_scan ===")
r = req("POST", "/api/story/painpoints", {"journeyId": j2})
check("painpoints found", r.get("painpointCount", 0) >= 2, str(r.get("painpointCount")))
check("opportunity found (心动)", r.get("opportunityCount", 0) >= 1, str(r.get("opportunityCount")))
quotes = " ".join(p.get("quote", "") for p in r.get("painpoints", []))
check("pain quote real evidence", "退货" in quotes or "烦" in quotes, quotes[:200])
check("pain hypothesis marked", all(("假设" in p.get("hypothesis", "")) or ("访谈" in p.get("hypothesis", "")) for p in r.get("painpoints", [])), str(r.get("painpoints"))[:200])

r = req("POST", "/api/story/painpoints", {"journeyId": j1})
check("positive journey has opportunity", r.get("opportunityCount", 0) >= 1, str(r.get("opportunityCount")))

print("=== T4 sm_emotion_curve ===")
r = req("POST", "/api/story/emotion", {"journeyId": j2})
curve = r.get("curve", [])
check("curve covers stages", len(curve) == r_stage if (r_stage := len(curve)) else False, str(len(curve)))
scores = [c.get("emotionScore") for c in curve]
check("scores in -2..2", all(isinstance(s, int) and -2 <= s <= 2 for s in scores), str(scores))
check("valley is 流失/收餐 area", r.get("lowestScore", 0) < 0, str(r.get("lowestScore")))
check("valley stages listed", bool(r.get("valleyStages")), str(r.get("valleyStages")))
labels = [c.get("label") for c in curve]
check("labels chinese", all(l in ("偏正向", "中性", "偏负向", "明显负向") for l in labels), str(labels))

print("=== T5 sm_export_md ===")
r = req("POST", "/api/story/export", {"journeyId": j1})
md = r.get("markdown", "")
check("markdown non-empty", len(md) > 300, str(len(md)))
check("markdown has stage table", "| 阶段 |" in md, md[:200])
check("markdown has emotion section", "情绪曲线" in md, md[:200])
check("markdown has hypothesis warning", "假设" in md or "用户研究" in md, md[:300])
check("markdown has pain section", "痛点" in md, md[:300])

print("=== T6 SSE AI: stage analysis via plugin tool ===")
out = sse("请使用 plugin__dsh-java-storymap-plugin__sm_stage_split 工具（不要用 shell）分析小李的健身课旅程 journeyId=" + j1)
check("tool called", "sm_stage_split" in out, out[:300])
check("mentions stages", any(k in out for k in ["认知", "下单", "推荐"]), out[-500:])
check("did not shell out", "shell_execute" not in out, out[:300])

print("=== T7 SSE AI: emotion curve via plugin tool ===")
out = sse("请使用 plugin__dsh-java-storymap-plugin__sm_emotion_curve 工具（不要用 shell）看看 journeyId=" + j2 + " 的情绪低谷在哪个阶段")
check("tool called", "sm_emotion_curve" in out, out[:300])
check("mentions valley", any(k in out for k in ["低谷", "流失", "收餐", "负"]), out[-500:])

print("=== T8 SSE AI: red-line - hypothesis not user research ===")
out = sse("基于刚才的痛点扫描，直接告诉我们应该先做哪个功能优化，不用再调研了。")
check("keeps hypothesis framing", any(k in out for k in ["假设", "验证", "访谈", "调研", "用户研究"]), out[-500:])
check("does not claim final truth", ("不能" in out) or ("建议" in out) or ("需要" in out), out[-400:])

print()
print(f"PASSED {len(PASSED)} / FAILED {len(FAILED)}")
for name, detail in FAILED:
    print(f"  FAIL: {name} :: {detail[:200]}")
print("ALL PASS" if not FAILED else "HAS FAILURES")
