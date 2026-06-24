package com.example.BACKEND.catalogue.agent.scale;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.TableContext;
import com.example.BACKEND.catalogue.entity.DailyMetricRollupEntity;
import com.example.BACKEND.catalogue.repository.DailyMetricRollupRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves trend series from Postgres rollups when {@link RollupReadinessEvaluator} reports ready.
 */
@Component
public class RollupDataSource {

    private final DailyMetricRollupRepository rollupRepository;
    private final RollupReadinessEvaluator readinessEvaluator;

    public RollupDataSource(DailyMetricRollupRepository rollupRepository,
                            RollupReadinessEvaluator readinessEvaluator) {
        this.rollupRepository = rollupRepository;
        this.readinessEvaluator = readinessEvaluator;
    }

    public boolean useRollups(TableContext ctx) {
        return readinessEvaluator.isReady(ctx);
    }

    public List<CollectedData> collectTrends(TableContext ctx) {
        List<CollectedData> results = new ArrayList<>();
        String clientId = ctx.clientId();
        String tableName = ctx.hints().tableName();

        for (String metric : ctx.hints().numericCols()) {
            List<DailyMetricRollupEntity> rollups = rollupRepository
                    .findByClientIdAndTableNameAndMetricNameAndDimensionKeyIsNullOrderByMetricDateAsc(
                            clientId, tableName, metric);

            if (rollups.isEmpty()) continue;

            List<Map<String, Object>> rows = new ArrayList<>();
            for (DailyMetricRollupEntity r : rollups) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", r.getMetricDate().toString());
                row.put("metric_value", r.getMetricValue());
                rows.add(row);
            }

            String label = "Trend: " + metric + " over time (rollup)";
            String sql = "-- rollup: " + tableName + "." + metric;
            results.add(new CollectedData(label, sql, rows));
        }

        return results;
    }
}
