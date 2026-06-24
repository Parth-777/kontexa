package com.example.BACKEND.catalogue.decision.candidate;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfile;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfiler;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupByExecutor;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationSpec;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializationSpec.SpecType;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.execution.materialization.NumericDimensionBucketer;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes a single analytical candidate against raw warehouse rows (in-memory GROUP BY).
 */
@Component
public class CandidateMaterializationExecutor {

    private static final Logger log = LoggerFactory.getLogger(CandidateMaterializationExecutor.class);

    private final SchemaProfiler schemaProfiler;
    private final GroupByExecutor groupByExecutor;
    private final NumericDimensionBucketer bucketer;
    private final PresentationLabelResolver labels;

    public CandidateMaterializationExecutor(
            SchemaProfiler schemaProfiler,
            GroupByExecutor groupByExecutor,
            NumericDimensionBucketer bucketer,
            PresentationLabelResolver labels
    ) {
        this.schemaProfiler = schemaProfiler;
        this.groupByExecutor = groupByExecutor;
        this.bucketer = bucketer;
        this.labels = labels;
    }

    public MaterializedQueryResult execute(
            List<Map<String, Object>> rows,
            AnalyticalCandidate candidate
    ) {
        if (rows == null || rows.isEmpty() || candidate == null) {
            return MaterializedQueryResult.empty();
        }

        SchemaProfile profile = schemaProfiler.profile(rows);
        if (!profile.hasValues()) return MaterializedQueryResult.empty();

        String valueCol = resolveValueColumn(rows.getFirst(), candidate, profile);
        if (valueCol == null) return MaterializedQueryResult.empty();

        String groupingKey = candidate.bucketColumn() != null && !candidate.bucketColumn().isBlank()
                ? candidate.bucketColumn()
                : candidate.plan().grouping();
        if (groupingKey == null || groupingKey.isBlank()) {
            return MaterializedQueryResult.empty();
        }

        String displayLabel = labels.resolveDimension(
                candidate.dimensionColumn() != null ? candidate.dimensionColumn() : groupingKey);

        MaterializationSpec spec = new MaterializationSpec(
                groupingKey,
                candidate.dimensionColumn() != null ? candidate.dimensionColumn() : groupingKey,
                displayLabel,
                SpecType.SOURCE_DIMENSION,
                0
        );

        List<MaterializationSpec> specs = List.of(spec);
        List<Map<String, Object>> bucketed = bucketer.materializeBucketColumns(rows, specs);

        String volumeCol = resolveVolumeColumn(rows.getFirst(), candidate, profile);

        MaterializedGrouping grouping = groupByExecutor.execute(
                bucketed, spec, valueCol, volumeCol);

        if (!grouping.hasData()) {
            log.debug("[candidate-exec] {} produced no groups", candidate.candidateId());
            return MaterializedQueryResult.empty();
        }

        String metricLabel = labels.resolveMetric(candidate.plan().primaryMetricLabel());
        return MaterializedQueryResult.grouped(
                List.of(grouping),
                grouping,
                List.of(),
                metricLabel,
                rows.size()
        );
    }

    private String resolveValueColumn(
            Map<String, Object> sampleRow,
            AnalyticalCandidate candidate,
            SchemaProfile profile
    ) {
        String requested = candidate.plan().primaryMetric();
        if (requested != null && sampleRow.containsKey(requested)) {
            return requested;
        }
        if (profile.primaryValue() != null) {
            return profile.primaryValue().columnName();
        }
        if (candidate.plan().aggregation() == AggregationType.AVG
                && "fare_amount".equals(requested)) {
            return findColumn(sampleRow, "fare_amount", "fare");
        }
        return findColumn(sampleRow, "total_amount", "fare_amount", "revenue", "amount");
    }

    private String resolveVolumeColumn(
            Map<String, Object> sampleRow,
            AnalyticalCandidate candidate,
            SchemaProfile profile
    ) {
        String metric = candidate.plan().primaryMetric();
        if (metric != null && metric.contains("per_mile")) {
            String dist = findColumn(sampleRow, "trip_distance", "distance", "miles");
            if (dist != null) return dist;
        }
        if (profile.primaryVolume() != null) {
            return profile.primaryVolume().columnName();
        }
        return findColumn(sampleRow, "trip_distance", "passenger_count", "volume");
    }

    private String findColumn(Map<String, Object> row, String... candidates) {
        for (String c : candidates) {
            if (row.containsKey(c)) return c;
            for (String key : row.keySet()) {
                if (key != null && key.toLowerCase(Locale.ROOT).contains(c)) return key;
            }
        }
        return null;
    }
}
