package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.presentation.ResponseMode;
import com.example.BACKEND.catalogue.decision.presentation.TableSpec;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationBuildContext;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationStrategy;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationStrategyResolver;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationStrategyType;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Semantic presentation engine: resolves strategy from {@link CanonicalQueryModel}
 * and builds {@link ExecutivePresentation} from warehouse rows only.
 */
@Component
public class ExecutivePresentationBuilder {

    private final PresentationStrategyResolver resolver;
    private final PresentationBuildContext context;
    private final Map<PresentationStrategyType, PresentationStrategy> strategies;
    private final ChartCardinalityReducer chartCardinalityReducer;

    public ExecutivePresentationBuilder(
            SemanticMetricFormatter formatter,
            ExecutivePresentationProperties properties,
            PresentationStrategyResolver resolver,
            List<PresentationStrategy> strategyList
    ) {
        this.resolver = resolver;
        this.context = new PresentationBuildContext(formatter, properties);
        this.chartCardinalityReducer = new ChartCardinalityReducer(
                properties != null ? properties.getChart().getTopN() : 5);
        this.strategies = new LinkedHashMap<>();
        for (PresentationStrategy strategy : strategyList) {
            strategies.put(strategy.type(), strategy);
        }
    }

    public ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> warehouseRows
    ) {
        if (model == null || warehouseRows == null || warehouseRows.isEmpty()) {
            return ExecutivePresentation.empty("SCALAR");
        }

        PresentationStrategyType type = resolver.resolve(model);
        PresentationStrategy strategy = strategies.get(type);
        if (strategy == null) {
            return ExecutivePresentation.empty(type.name());
        }
        return strategy.build(model, List.copyOf(warehouseRows), context);
    }

    public ResponseMode responseMode(ExecutivePresentation presentation) {
        if (presentation == null) {
            return ResponseMode.KPI;
        }
        return switch (presentation.type()) {
            case "SCALAR" -> ResponseMode.KPI;
            case "CORRELATION" -> ResponseMode.CHART;
            case "RANKING", "DISTRIBUTION", "TREND", "GROWTH", "PARETO", "OUTLIER", "VARIANCE",
                    "CONTRIBUTION", "COMPARISON" -> ResponseMode.MIXED;
            default -> ResponseMode.MIXED;
        };
    }

    public TableSpec toTableSpec(ExecutivePresentation presentation) {
        if (presentation == null || presentation.table() == null || !presentation.table().hasContent()) {
            return TableSpec.empty();
        }
        var table = presentation.table();
        List<TableSpec.Column> columns = table.columns().stream()
                .map(c -> new TableSpec.Column(c.key(), c.label(), c.format()))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, String> formatted : table.rows()) {
            rows.add(new LinkedHashMap<>(formatted));
        }
        return new TableSpec(columns, rows, table.title(), true, true);
    }

    public ChartSpec toChartSpec(ExecutivePresentation presentation, List<Map<String, Object>> warehouseRows) {
        if (presentation == null || presentation.charts().isEmpty() || warehouseRows == null) {
            return null;
        }
        ExecutivePresentation.ChartHint hint = presentation.charts().getFirst();
        ChartSpec.ChartType chartType = mapChartType(hint.chartType());
        if (chartType == null) {
            return null;
        }

        // Cardinality-aware: the chart visualizes only the most important categories (Top-N + "Other"),
        // while the warehouse rows, statistics, and executive table remain complete.
        String categoryKey = hint.categoryKey() != null ? hint.categoryKey() : hint.xKey();
        String valueKey = hint.valueKey() != null ? hint.valueKey() : hint.yKey();
        ChartCardinalityReducer.Result reduced = chartCardinalityReducer.reduce(
                presentation.type(), hint.chartType(), categoryKey, valueKey, warehouseRows);

        ChartSpec spec = new ChartSpec(
                chartType,
                hint.title(),
                reduced.notice(),
                hint.categoryKey(),
                hint.valueKey(),
                hint.xKey(),
                hint.yKey(),
                hint.valueFormat(),
                chartType == ChartSpec.ChartType.LINE ? "date" : "category",
                reduced.data());
        spec.setDisplayedRows(reduced.displayedRows());
        spec.setTotalRows(reduced.totalRows());
        spec.setAggregatedRows(reduced.aggregatedRows());
        return spec;
    }

    public List<ExecutiveSupportingMetric> toSupportingMetrics(ExecutivePresentation presentation) {
        if (presentation == null || presentation.kpis().isEmpty()) {
            return List.of();
        }
        return presentation.kpis().stream()
                .map(k -> new ExecutiveSupportingMetric(
                        k.label(),
                        k.formattedValue(),
                        k.unit() != null ? k.unit() : "",
                        ""))
                .toList();
    }

    private static ChartSpec.ChartType mapChartType(String chartType) {
        if (chartType == null) {
            return null;
        }
        return switch (chartType.toUpperCase(Locale.ROOT)) {
            case "BAR" -> ChartSpec.ChartType.BAR;
            case "HBAR" -> ChartSpec.ChartType.HBAR;
            case "LINE" -> ChartSpec.ChartType.LINE;
            case "DONUT", "PIE" -> ChartSpec.ChartType.DONUT;
            case "CORRELATION", "SCATTER" -> ChartSpec.ChartType.CORRELATION;
            default -> null;
        };
    }
}
