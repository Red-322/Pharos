package com.pharos.rag.qa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 查询规划结果，包含大模型分析出的检索策略和检索语句列表。
 * <p>
 * 由 {@code QueryPlanningService} 调用大模型生成，内容会经过校验和规范化处理。
 * 未知字段将被忽略（{@code @JsonIgnoreProperties(ignoreUnknown = true)}）。
 * </p>
 *
 * <p>
 * {@code queries}     → 走 向量 + 关键词 双通道检索
 * {@code hydeQueries} → HyDE 生成的"假设答案"文本，只走向量通道
 *                        （长段落不适合灌进 BM25/IK 关键词通道）
 * </p>
 *
 * @param strategy 查询规划策略，取值见 {@link QueryPlanStrategy}
 * @param queries  经规划后的检索语句列表，最多 3 条
 * @param hydeQueries 装HyDE文本
 * @param keywordQueries 改写或者子查询 只走向量通道
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryPlanResult(
        QueryPlanStrategy strategy,
        List<String> queries,
        List<String> hydeQueries,
        List<String> keywordQueries
) {
    /**
     *紧凑构造器：只要有人（包括Jackson、两参构造器、withHydeQueries）创建这个对象
     *都会先执行这里，把 null 统一变成空集合，彻底杜绝 NPE
     * */
    public QueryPlanResult {
        if (queries == null) queries = List.of();
        if (hydeQueries == null) hydeQueries = List.of();
        if (keywordQueries == null) keywordQueries = List.of();
    }

    /**
     * 旧调用点：老的 new QueryPlanResult(strategy, queries)还能编译
     * hydeQueries 自动补成空列表
     */
    public QueryPlanResult(QueryPlanStrategy strategy, List<String> queries) {
        this(strategy, queries, List.of(),  List.of());
    }

    /**
     *复制自己，换掉 hydeQueries，返回新对象
     */
    public QueryPlanResult withHydeQueries(List<String> hydeQueries) {
        return new QueryPlanResult(this.strategy, this.queries, hydeQueries, this.keywordQueries);
    }

    /**
     * 创建回退结果：采用 DIRECT 策略，直接使用原始问题作为检索语句。
     * <p>当查询规划失败或结果无效时用作安全回退。</p>
     *
     * @param question 原始用户问题
     * @return 回退的查询规划结果
     */
    public static QueryPlanResult fallback(String question) {
        return new QueryPlanResult(QueryPlanStrategy.DIRECT, List.of(question));
    }
}
