package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.CollectedData;
import com.example.BACKEND.catalogue.agent.SignalDetectionService;
import com.example.BACKEND.catalogue.entity.SignalEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs signal detection and formats material changes for the executive pipeline.
 */
@Component
public class SignalSummaryBuilder {

    private final SignalDetectionService signalDetectionService;

    public SignalSummaryBuilder(SignalDetectionService signalDetectionService) {
        this.signalDetectionService = signalDetectionService;
    }

    public List<SignalEntity> detectAndSummarize(String clientId, JsonNode catalogueNode,
                                                  List<CollectedData> collected) {
        List<SignalEntity> signals = signalDetectionService.detectSignals(clientId, catalogueNode);
        CollectedData summary = toCollectedData(signals);
        if (summary != null) {
            collected.add(summary);
        }
        return signals;
    }

    public CollectedData toCollectedData(List<SignalEntity> signals) {
        if (signals == null || signals.isEmpty()) return null;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SignalEntity s : signals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", s.getTableName());
            row.put("column", s.getColumnName());
            row.put("signal_type", s.getSignalType());
            row.put("delta_pct", s.getDeltaPct());
            row.put("significance", s.getSignificance());
            row.put("current_value", s.getValue());
            row.put("baseline_value", s.getBaseline());
            rows.add(row);
        }
        return new CollectedData("SIGNALS: material changes since baseline", "-- signals", rows);
    }
}
