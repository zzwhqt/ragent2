package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.IntentParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 意图定向检索通道
 * <p>
 * 基于意图识别结果，在特定知识库中进行定向检索
 * 这是最精确的检索方式，优先级最高
 */
@Slf4j
@Component
public class IntentDirectedSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final IntentParallelRetriever parallelRetriever;

    /**
     * 构造方法：初始化意图定向检索通道
     *
     * @param retrieverService 检索服务，用于执行向量检索
     * @param properties 搜索通道配置属性
     * @param ragInnerRetrievalExecutor 内部检索线程池，用于并行检索多个意图对应的知识库
     */
    public IntentDirectedSearchChannel(RetrieverService retrieverService,
                                       SearchChannelProperties properties,
                                       @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor ragInnerRetrievalExecutor) {
        this.properties = properties;
        this.parallelRetriever = new IntentParallelRetriever(retrieverService, ragInnerRetrievalExecutor);
    }

    /**
     * 获取通道名称
     *
     * @return 通道名称标识符 "IntentDirectedSearch"
     */
    @Override
    public String getName() {
        return "IntentDirectedSearch";
    }

    /**
     * 获取通道优先级
     *
     * @return 优先级数值，1表示最高优先级（最精确的检索方式）
     */
    @Override
    public int getPriority() {
        return 1;  // 最高优先级
    }

    /**
     * 判断是否启用意图定向检索通道
     * 需同时满足以下条件：
     * 1. 配置开关已启用
     * 2. 存在意图识别结果
     * 3. 存在KB类型的意图节点且分数达到阈值
     *
     * @param context 搜索上下文，包含意图识别结果和配置信息
     * @return true-启用意图定向检索，false-不启用
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        // 检查配置是否启用
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return false;
        }

        // 检查是否有 KB 意图（而不仅仅是有意图）
        if (CollUtil.isEmpty(context.getIntents())) {
            return false;
        }

        // 提取 KB 意图，只有存在 KB 意图时才启用
        List<NodeScore> kbIntents = extractKbIntents(context);
        return CollUtil.isNotEmpty(kbIntents);
    }

    /**
     * 执行意图定向检索
     * 根据识别出的KB意图，在对应的知识库中并行检索，返回合并后的结果
     *
     * @param context 搜索上下文，包含用户问题、意图识别结果、topK等参数
     * @return 检索结果，包含检索到的chunks、置信度（基于意图分数）、耗时和元数据
     */
    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            // 提取 KB 意图
            List<NodeScore> kbIntents = extractKbIntents(context);

            if (CollUtil.isEmpty(kbIntents)) {
                log.warn("意图定向检索通道被启用，但未找到 KB 意图（不应该发生）");
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.INTENT_DIRECTED)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            log.info("执行意图定向检索，识别出 {} 个 KB 意图", kbIntents.size());

            // 并行检索所有意图对应的知识库
            int topKMultiplier = properties.getChannels().getIntentDirected().getTopKMultiplier();
            List<RetrievedChunk> allChunks = retrieveByIntents(
                    context.getMainQuestion(),
                    kbIntents,
                    context.getTopK(),
                    topKMultiplier
            );

            // 计算置信度（基于意图分数）
            double confidence = kbIntents.stream()
                    .mapToDouble(NodeScore::getScore)
                    .max()
                    .orElse(0.0);

            long latency = System.currentTimeMillis() - startTime;

            log.info("意图定向检索完成，检索到 {} 个 Chunk，置信度：{}，耗时 {}ms",
                    allChunks.size(), confidence, latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(allChunks)
                    .confidence(confidence)
                    .latencyMs(latency)
                    .metadata(Map.of("intentCount", kbIntents.size()))
                    .build();

        } catch (Exception e) {
            log.error("意图定向检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 获取通道类型枚举
     *
     * @return 通道类型 INTENT_DIRECTED
     */
    @Override
    public SearchChannelType getType() {
        return SearchChannelType.INTENT_DIRECTED;
    }

    /**
     * 从上下文中提取KB类型的意图节点
     * 过滤条件：
     * 1. 节点类型为KB
     * 2. 意图分数不低于配置的最小阈值
     *
     * @param context 搜索上下文，包含所有意图识别结果
     * @return 符合条件的KB意图节点列表
     */
    private List<NodeScore> extractKbIntents(SearchContext context) {
        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        return context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .filter(ns -> ns.getScore() >= minScore)
                .toList();
    }

    /**
     * 根据KB意图列表并行检索对应的知识库
     *
     * @param question 用户查询问题
     * @param kbIntents KB意图节点列表，每个节点包含对应的知识库信息
     * @param fallbackTopK 基础topK数量
     * @param topKMultiplier topK倍数，实际检索数量为 fallbackTopK * topKMultiplier
     * @return 合并后的检索结果列表
     */
    private List<RetrievedChunk> retrieveByIntents(String question,
                                                   List<NodeScore> kbIntents,
                                                   int fallbackTopK,
                                                   int topKMultiplier) {
        // 使用模板方法执行并行检索
        return parallelRetriever.executeParallelRetrieval(question, kbIntents, fallbackTopK, topKMultiplier);
    }
}