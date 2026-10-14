package com.pharos.rag.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pharos.rag.metrics.model.entity.LlmUsageRecordEntity;
import com.pharos.rag.metrics.model.vo.MetricsOverviewVO;
import com.pharos.rag.metrics.model.vo.UsageRankItemVO;
import com.pharos.rag.metrics.model.vo.UsageStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LlmUsageRecordMapper extends BaseMapper<LlmUsageRecordEntity> {
    // BaseMapper 提供 insert、selectById 等基础方法
    // 复杂统计查询放在 XML 中

    // 通用统计查询
    UsageStatsVO selectUsageStats(
            @Param("userId") Long userId,
            @Param("groupId") Long groupId,
            @Param("startTime") LocalDateTime startTime
    );

    // 每日趋势
    List<MetricsOverviewVO.DailyStats> selectDailyTrend(
            @Param("startTime") LocalDateTime startTime,
            @Param("module") String module
    );

    // 用户排行
    List<UsageRankItemVO> selectUserRank(
            @Param("startTime") LocalDateTime startTime,
            @Param("limit") int limit
    );

    // 群组排行
    List<UsageRankItemVO> selectGroupRank(
            @Param("startTime") LocalDateTime startTime,
            @Param("limit") int limit
    );
}
