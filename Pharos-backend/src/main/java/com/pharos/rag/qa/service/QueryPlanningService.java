package com.pharos.rag.qa.service;

import com.pharos.rag.common.exception.BusinessException;
import com.pharos.rag.qa.model.QueryPlanResult;
import com.pharos.rag.qa.model.QueryPlanStrategy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询规划服务。
 * <p>
 * 调用大模型分析用户问题，决定采用哪种检索策略（直接、重写或分解），
 * 并生成对应的检索语句列表。规划失败时自动回退为 DIRECT 策略。
 * </p>
 */
@Service
public class QueryPlanningService {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanningService.class);

    /** 检索语句最大数量限制 */
    private static final int MAX_QUERY_COUNT = 3;

    private final ChatClient queryPlanningChatClient;
    private final PromptTemplate queryPlanningUserPromptTemplate;

    /** HyDE 专用的 ChatClient */
    private final ChatClient hydeChatClient;
    private final PromptTemplate hydeUserPromptTemplate;

    /** HyDE 开关，默认关 */
    @Value("${qa.query-planning.hyde-enabled:false}")
    private boolean hydeEnabled;

    /** HyDE 缓存：同一个问题不重复花钱 */
    private final Cache<String, String> hydeCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /**
     * 构造函数。
     *
     * @param queryPlanningChatClient         查询规划专用的 ChatClient
     * @param queryPlanningUserPromptTemplate 查询规划用户提示词模板
     * @param hydeChatClient                  HyDE 专用的 ChatClient
     * @param hydeUserPromptTemplate          HyDE 用户提示词模板
     */
    public QueryPlanningService(
            @Qualifier("queryPlanningChatClient") ChatClient queryPlanningChatClient,
            @Qualifier("queryPlanningUserPromptTemplate") PromptTemplate queryPlanningUserPromptTemplate,
            @Qualifier("hydeChatClient") ChatClient hydeChatClient,
            @Qualifier("hydeUserPromptTemplate") PromptTemplate hydeUserPromptTemplate

    ) {
        this.queryPlanningChatClient = queryPlanningChatClient;
        this.queryPlanningUserPromptTemplate = queryPlanningUserPromptTemplate;
        this.hydeChatClient = hydeChatClient;
        this.hydeUserPromptTemplate = hydeUserPromptTemplate;
    }

    /**
     * 对用户问题执行查询规划。
     * <p>
     * 调用大模型分析问题并返回检索策略和检索语句，
     * 失败时回退为 DIRECT 策略。
     * </p>
     *
     * @param question 用户原始问题
     * @return 查询规划结果
     */
    public QueryPlanResult plan(String question) {
        String normalizedQuestion = requireQuestion(question);
        try {

            Prompt planPrompt = queryPlanningUserPromptTemplate.create(Map.of("question", normalizedQuestion));

            QueryPlanResult rawResult = queryPlanningChatClient.prompt(planPrompt)
//                    .user(user -> user.text(renderUserPrompt(normalizedQuestion)))
                    .call()
                    .entity(QueryPlanResult.class);
            QueryPlanResult validatedPlan = validatePlan(rawResult, normalizedQuestion);

            //HyDE改造点
            QueryPlanResult finalPlan = appendHydeQuery(validatedPlan, normalizedQuestion);

            log.info("查询规划完成: strategy={}, queries={}, hydeQueries={}， keywordQueries={}", finalPlan.strategy(), finalPlan.queries(), finalPlan.hydeQueries(), finalPlan.keywordQueries());
            return finalPlan;
        } catch (RuntimeException exception) {
            log.warn("查询规划失败，回退为直接检索: question={}", normalizedQuestion, exception);
            return QueryPlanResult.fallback(normalizedQuestion);
        }
    }

    /**
     * HyDE 的核心动作，三步：
     *   1) 判断开关，关了就原样返回
     *   2) 生成 hydeText（走缓存）
     *   3) 把 hydeText 装进 plan，返回新对象
     * 任何一步失败 → 降级回原 plan，绝不让问答挂掉。
     */
    private QueryPlanResult appendHydeQuery(QueryPlanResult plan, String question) {
        if (!hydeEnabled){
            return plan;
        }
        try{
            //调LLM（走缓存）
            String hydeText = hydeCache.get(question, this::generateHydeText);
            //无生成，降级
            if (!StringUtils.hasText(hydeText)) {
                return plan;
            }
            return plan.withHydeQueries(List.of(hydeText));//把 hydeText 塞进 plan
        } catch (RuntimeException e) {
            log.warn("HyDE 生成失败，降级为原始规划结果：question={}", question, e);
            return plan;
        }
    }

    /**
     * 第 2 次 LLM 调用，用于HyDE，不是重复规划，是一次全新的生成。
     */
    private String generateHydeText(String question) {
        Prompt hydePrompt = hydeUserPromptTemplate.create(Map.of("question", question));
        String hydeText = hydeChatClient.prompt(hydePrompt)
                .call()
                .content();
        return hydeText == null ? null : hydeText.replaceAll("\\s+", " ").trim();
    }

    /**
     * 校验规划结果，确保策略和检索语句有效。
     * 根据不同策略构建最终的检索语句列表。
     */
    private QueryPlanResult validatePlan(QueryPlanResult rawResult, String originalQuestion) {
        if (rawResult == null || rawResult.strategy() == null) {
            return QueryPlanResult.fallback(originalQuestion);
        }
        if (rawResult.strategy() == QueryPlanStrategy.GLOBAL) {
            return new QueryPlanResult(QueryPlanStrategy.GLOBAL, List.of(originalQuestion));
        }
        Set<String> normalizedQueries = normalizeQueries(rawResult.queries());
        if (normalizedQueries.isEmpty()) {
            return QueryPlanResult.fallback(originalQuestion);
        }

        // 定义两个列表
        List<String> finalQueries;    // 走双通道（向量 + 关键词）
        List<String> keywordQueries;  // 仅走关键词通道

        // 根据策略分流
        switch (rawResult.strategy()) {
            case DIRECT -> {
                finalQueries = List.of(originalQuestion);
                keywordQueries = List.of(); // DIRECT 不需要额外的关键词
            }
            case REWRITE -> {
                finalQueries = List.of(originalQuestion); // 原问题走双通道
                // 改写短语只走关键词，最多取2条 (截断保护)
                keywordQueries = normalizedQueries.stream().limit(2).toList();
            }
            case DECOMPOSE -> {
                // 原来这里没有原问题，现在必须把原问题补上走双通道
                finalQueries = List.of(originalQuestion);
                // 子查询只走关键词，最多取2条
                keywordQueries = normalizedQueries.stream().limit(2).toList();
            }
            default -> { // 兜底
                finalQueries = List.of(originalQuestion);
                keywordQueries = List.of();
            }
        }
        if (finalQueries.isEmpty()) {
            finalQueries = List.of(originalQuestion);
        }
        // 最后构建时，需要把 keywordQueries 传进去（使用4参构造器）
        // 第3位传 List.of() 是故意的。HyDE 不由 validatePlan 生成，而是由后续的 appendHydeQuery() 通过 withHydeQueries() 统一注入。
        return new QueryPlanResult(rawResult.strategy(), finalQueries, List.of(), keywordQueries);
    }

    /** 构建 REWRITE 策略的检索语句：原始问题 + 重写后的语句 */
    private List<String> buildRewriteQueries(String originalQuestion, Set<String> normalizedQueries) {
        LinkedHashSet<String> rewriteQueries = new LinkedHashSet<>();
        rewriteQueries.add(originalQuestion);
        rewriteQueries.addAll(normalizedQueries);
        return limitQueries(rewriteQueries);
    }

    /** 规范化检索语句：去除空白、去重 */
    private Set<String> normalizeQueries(List<String> queries) {
        LinkedHashSet<String> normalizedQueries = new LinkedHashSet<>();
        if (queries == null) {
            return normalizedQueries;
        }
        for (String query : queries) {
            if (!StringUtils.hasText(query)) {
                continue;
            }
            String normalized = query.replaceAll("\\s+", " ").trim();
            if (StringUtils.hasText(normalized)) {
                normalizedQueries.add(normalized);
            }
        }
        return normalizedQueries;
    }

    /** 限制检索语句数量不超过 {@link #MAX_QUERY_COUNT} */
    private List<String> limitQueries(Set<String> queries) {
        return queries.stream()
                .limit(MAX_QUERY_COUNT)
                .toList();
    }

    /** 渲染用户提示词模板（当前未使用，保留备用） */
    private String renderUserPrompt(String question) {
        return queryPlanningUserPromptTemplate.render(Map.of("question", question));
    }

    /** 校验问题非空并规范化空白字符 */
    private String requireQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new BusinessException("问题不能为空");
        }
        return question.replaceAll("\\s+", " ").trim();
    }
}
