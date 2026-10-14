package com.pharos.rag.eval;

import com.pharos.rag.qa.rag.HybridChunkRetrievalService;
import com.pharos.rag.qa.rag.RetrievedEvidenceBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.*;

/**
 * RAG 检索质量评测器。
 *
 * <p><b>这是整个改造工程的标尺。</b>没有它,你后面做的每一个改造
 * 都只能说"感觉好了一点";有了它,你才能说"Top5 命中率从 62% 提到 78%"。
 * 这两句话在面试官耳朵里完全不是一回事。</p>
 *
 * <h3>怎么用</h3>
 * <ol>
 *   <li>把 questions.example.json 复制成 questions.json,按你自己的文档填 40 条</li>
 *   <li>两个文件都放到 Pharos-backend/src/test/resources/eval/ 下</li>
 *   <li>把本类放到 Pharos-backend/src/test/java/com/pharos/rag/eval/ 下</li>
 *   <li>在 IDEA 里运行（本机没装 mvn，别用命令行）</li>
 *   <li><b>把第一次跑出来的数字记下来</b> —— 那就是基线,是简历上"从 X 提升到 Y"的 X</li>
 * </ol>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code expectedDocumentId} —— 答案所在文档 ID(必需)</li>
 *   <li>{@code expectedChunkIndexes} —— 答案所在切片序号(可选,填了才算 chunk 级命中)</li>
 * </ul>
 *
 * <p>检索返回的是<b>聚类后</b>的结果(一个 Document 可能覆盖多个相邻切片),
 * 所以命中判定同时看 {@code chunkId} 和 {@code primaryChunkId}。</p>
 */
@SpringBootTest
public class EvalRunner {

    @Autowired
    private HybridChunkRetrievalService retrievalService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 评测群组 ID —— 改成你自己建的那个群组 */
    private static final Long GROUP_ID = 1L;
    /** 每道题检索 Top K */
    private static final int TOP_K = 10;

    // ============ 题目模型 ============

    public record EvalQuestion(
            int id,
            String question,
            Long expectedDocumentId,
            List<Integer> expectedChunkIndexes,
            String difficulty,
            String note,
            String questionType
    ) {}

    public record EvalResult(
            int id,
            String difficulty,
            boolean docHit1,
            boolean docHit5,
            boolean chunkHit5,
            int firstHitRank,   // 首次命中的排名(1-based),0 表示未命中
            long latencyMs,
            String questionType
    ) {}

    // ============ 主流程 ============

    @Test
    public void runEval() throws Exception {
        List<EvalQuestion> questions = loadQuestions();
        System.out.println("\n========== RAG 检索评测开始 ==========");
        System.out.printf("题目数: %d | 群组: %d | TopK: %d%n%n", questions.size(), GROUP_ID, TOP_K);

        List<EvalResult> results = new ArrayList<>();
        for (EvalQuestion q : questions) {
            results.add(evaluateOne(q));
        }

        report(results);
    }

    private EvalResult evaluateOne(EvalQuestion q) {
        long start = System.currentTimeMillis();
        RetrievedEvidenceBundle bundle;
        try {
            bundle = retrievalService.retrieve(GROUP_ID, q.question(), TOP_K);
        } catch (Exception e) {
            System.err.printf("[%d] 检索失败: %s%n", q.id(), e.getMessage());
            return new EvalResult(q.id(), q.difficulty(), false, false, false, 0,
                    System.currentTimeMillis() - start, q.questionType());
        }
        long latency = System.currentTimeMillis() - start;

        List<Document> docs = bundle.documents() == null ? List.of() : bundle.documents();

        int firstHitRank = 0;
        for (int i = 0; i < docs.size(); i++) {
            if (isHit(docs.get(i), q)) {
                firstHitRank = i + 1;
                break;
            }
        }

        boolean docHit1   = docs.size() > 0 && isDocHit(docs.get(0), q);
        boolean docHit5   = docs.stream().limit(5).anyMatch(d -> isDocHit(d, q));
        boolean chunkHit5 = docs.stream().limit(5).anyMatch(d -> isChunkHit(d, q));

        return new EvalResult(q.id(), q.difficulty(), docHit1, docHit5, chunkHit5,
                firstHitRank, latency, q.questionType());
    }

    // ============ 命中判定 ============

    /** 文档级命中:检索到的文档 ID 与标注一致 */
    private boolean isDocHit(Document doc, EvalQuestion q) {
        Object docId = doc.getMetadata().get("documentId");
        if (docId == null || q.expectedDocumentId() == null) return false;
        return Objects.equals(toLong(docId), q.expectedDocumentId());
    }

    /**
     * 切片级命中:标注的切片被检索结果覆盖。
     *
     * <p>分两级判定,顺序不能反:</p>
     * <ol>
     *   <li><b>精确命中</b> —— 标注切片就是返回结果的主切片(chunkIndex / primaryChunkIndex)</li>
     *   <li><b>窗口命中</b> —— 标注切片落在邻居扩展区间内(startChunkIndex ~ endChunkIndex)</li>
     * </ol>
     *
     * <p><b>为什么必须有第 2 级:</b>这个项目的检索返回的是"聚类 + 邻居窗口扩展"之后的结果,
     * 一个 Document 覆盖的是[start, end]一段连续切片,而 {@code chunkIndex} 只记主切片的位置。
     * 如果只判精确位置,那些"答案确实被正确地捞回来了、但它落在窗口内相邻切片上"的结果
     * 会被算成未命中 —— 你的基线数字就会偏低,后面所有改造的对比数据跟着一起失真。</p>
     *
     * <p><b>想拿更严格的指标:</b>把第 2 级判定注释掉,就得到"主切片必须精确命中"的严标准。
     * 面试时你也可以两个都报 —— 能解释清楚"为什么我用了窗口级判定,以及它和精确判定差多少",
     * 比只报一个数字更显功底。</p>
     */
    private boolean isChunkHit(Document doc, EvalQuestion q) {
        if (!isDocHit(doc, q)) return false;
        List<Integer> expected = q.expectedChunkIndexes();
        if (expected == null || expected.isEmpty()) return false;

        // 第 1 级:精确命中(主切片)
        Set<Integer> hitIndexes = new HashSet<>();
        for (String key : List.of("chunkIndex", "primaryChunkIndex")) {
            Object v = doc.getMetadata().get(key);
            if (v != null) hitIndexes.add(toInt(v));
        }
        for (Integer e : expected) {
            if (hitIndexes.contains(e)) return true;
        }

        // 第 2 级:窗口命中(落在邻居扩展区间内)
        Object startRaw = doc.getMetadata().get("startChunkIndex");
        Object endRaw = doc.getMetadata().get("endChunkIndex");
        if (startRaw != null && endRaw != null) {
            int from = toInt(startRaw);
            int to = toInt(endRaw);
            for (Integer idx : expected) {
                if (idx >= from && idx <= to) return true;
            }
        }
        return false;
    }

    private boolean isHit(Document doc, EvalQuestion q) {
        // 标注了切片就按切片判,否则退化为文档级
        List<Integer> expected = q.expectedChunkIndexes();
        if (expected != null && !expected.isEmpty()) {
            return isChunkHit(doc, q);
        }
        return isDocHit(doc, q);
    }

    // ============ 报告输出 ============

    private void report(List<EvalResult> rs) {
        int n = rs.size();
        double top1  = rs.stream().filter(r -> r.docHit1()).count()   * 100.0 / n;
        double top5  = rs.stream().filter(r -> r.docHit5()).count()   * 100.0 / n;
        double chit5 = rs.stream().filter(r -> r.chunkHit5()).count() * 100.0 / n;
        double mrr   = rs.stream().mapToDouble(r -> r.firstHitRank() > 0 ? 1.0 / r.firstHitRank() : 0.0)
                        .average().orElse(0.0);
        double avgLat = rs.stream().mapToLong(EvalResult::latencyMs).average().orElse(0.0);
        double p95Lat = rs.stream().mapToLong(EvalResult::latencyMs).sorted()
                        .skip(Math.max(0, (long)(n * 0.95) - 1)).findFirst().orElse(0);

        System.out.println("---- 逐题明细 ----");
        System.out.printf("%-4s %-8s %-8s %-8s %-8s %-6s %s%n",
                "ID", "难度", "DocTop1", "DocTop5", "ChunkT5", "首位", "耗时ms");
        for (EvalResult r : rs) {
            System.out.printf("%-4d %-8s %-8s %-8s %-8s %-6s %d%n",
                    r.id(), r.difficulty(),
                    r.docHit1() ? "Y" : "-", r.docHit5() ? "Y" : "-", r.chunkHit5() ? "Y" : "-",
                    r.firstHitRank() > 0 ? r.firstHitRank() : "-", r.latencyMs());
        }

        System.out.println("\n---- 汇总(记下这些数字,这就是基线)----");
        System.out.printf("Doc  Top1 命中率 : %.1f%%%n", top1);
        System.out.printf("Doc  Top5 命中率 : %.1f%%%n", top5);
        System.out.printf("Chunk Top5 命中率: %.1f%%%n", chit5);
        System.out.printf("MRR            : %.3f%n", mrr);
        System.out.printf("平均耗时        : %.0f ms%n", avgLat);
        System.out.printf("P95 耗时        : %.0f ms%n", p95Lat);

        //分题型
        System.out.println("\n---- 分题型 Top5 ----");
        List<String> questionTypes = rs.stream().
                map(EvalResult::questionType).
                distinct().
                toList();
        for (String t : questionTypes){
            List<EvalResult> sub = rs.stream().filter(r -> t.equals(r.questionType())).toList();
            double t5 = sub.stream().filter(EvalResult::docHit5).count() * 100.0 / sub.size();
            System.out.printf("%-8s %d 题  Top5: %.1f%%%n", t, sub.size(), t5);
        }

        // 分难度
        System.out.println("\n---- 分难度 Top5 ----");
        for (String d : List.of("EASY", "MEDIUM", "HARD")) {
            List<EvalResult> sub = rs.stream().filter(r -> d.equals(r.difficulty())).toList();
            if (sub.isEmpty()) continue;
            double t5 = sub.stream().filter(EvalResult::docHit5).count() * 100.0 / sub.size();
            System.out.printf("%-8s %d 题  Top5: %.1f%%%n", d, sub.size(), t5);
        }
        System.out.println("======================================\n");
    }

    // ============ 工具 ============

    private List<EvalQuestion> loadQuestions() throws Exception {
        ClassPathResource res = new ClassPathResource("eval/questions.json");
        try (InputStream in = res.getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<EvalQuestion>>() {});
        }
    }

    private static Long toLong(Object v) {
        if (v instanceof Number num) return num.longValue();
        return Long.parseLong(String.valueOf(v));
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number num) return num.intValue();
        return Integer.parseInt(String.valueOf(v));
    }
}
