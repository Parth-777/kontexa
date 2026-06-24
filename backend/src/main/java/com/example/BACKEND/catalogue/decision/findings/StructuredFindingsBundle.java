package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The complete set of structured analytical findings for one decision run.
 *
 * This bundle replaces flat observations as the primary input to {@link
 * com.example.BACKEND.catalogue.decision.synthesis.ExecutiveSynthesisService}.
 *
 * The LLM narrates FINDINGS — not raw warehouse outputs.
 *
 * primaryFindingType — the dominant finding type for this query (drives synthesis framing)
 * hasStructuredFindings — if false, synthesis must fallback to raw evidence rendering
 */
public record StructuredFindingsBundle(
        List<ContributionFinding>     contributionFindings,
        List<RankingFinding>          rankingFindings,
        List<EfficiencyFinding>       efficiencyFindings,
        List<TemporalPatternFinding>  temporalFindings,
        List<ComparativeFinding>      comparativeFindings,
        List<CorrelationFinding>      correlationFindings,
        FindingType                   primaryFindingType,
        int                           totalFindingCount,
        boolean                       hasStructuredFindings
) {

    /** All findings in one ordered stream (by magnitude descending). */
    public List<AnalyticalFinding> allFindings() {
        return Stream.of(
                contributionFindings.stream().map(f -> (AnalyticalFinding) f),
                rankingFindings.stream().map(f -> (AnalyticalFinding) f),
                efficiencyFindings.stream().map(f -> (AnalyticalFinding) f),
                temporalFindings.stream().map(f -> (AnalyticalFinding) f),
                comparativeFindings.stream().map(f -> (AnalyticalFinding) f),
                correlationFindings.stream().map(f -> (AnalyticalFinding) f)
        ).flatMap(s -> s)
         .sorted((a, b) -> Double.compare(b.magnitude(), a.magnitude()))
         .toList();
    }

    public static StructuredFindingsBundle empty() {
        return new StructuredFindingsBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                FindingType.RANKING, 0, false);
    }

    /** Rebuild typed lists from grounded findings (post semantic validation). */
    public static StructuredFindingsBundle fromGroundedFindings(
            List<AnalyticalFinding> grounded,
            FindingType primaryType
    ) {
        if (grounded == null || grounded.isEmpty()) return empty();

        List<ContributionFinding> contributions = new ArrayList<>();
        List<RankingFinding> rankings = new ArrayList<>();
        List<EfficiencyFinding> efficiencies = new ArrayList<>();
        List<TemporalPatternFinding> temporals = new ArrayList<>();
        List<ComparativeFinding> comparatives = new ArrayList<>();
        List<CorrelationFinding> correlations = new ArrayList<>();

        for (AnalyticalFinding f : grounded) {
            switch (f) {
                case ContributionFinding c -> contributions.add(c);
                case RankingFinding r -> rankings.add(r);
                case EfficiencyFinding e -> efficiencies.add(e);
                case TemporalPatternFinding t -> temporals.add(t);
                case ComparativeFinding c -> comparatives.add(c);
                case CorrelationFinding c -> correlations.add(c);
            }
        }

        FindingType primary = primaryType != null ? primaryType : grounded.getFirst().findingType();
        int total = contributions.size() + rankings.size() + efficiencies.size()
                + temporals.size() + comparatives.size() + correlations.size();

        return new StructuredFindingsBundle(
                contributions, rankings, efficiencies, temporals, comparatives, correlations,
                primary, total, total > 0);
    }
}
