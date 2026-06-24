package com.example.BACKEND.catalogue.decision.semantics;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects metric–metric relationship questions that do not require a grouping dimension.
 */
@Component
public class RelationshipIntentDetector {

    private static final Pattern AFFECT = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+(?:affect|impact|influence|drive|hurt)\\s+(.+?)\\??$");

    private static final Pattern DOES_AFFECT = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+(?:affect|impact|influence|hurt|drive)\\s+(.+?)\\??$");

    private static final Pattern CORRELATE = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+correlat(?:e|es|ion)?\\s+with\\s+(.+?)\\??$");

    private static final Pattern RELATE = Pattern.compile(
            "(?i)how\\s+(?:does|do)\\s+(.+?)\\s+relat(?:e|es|ion)?\\s+(?:to|with)\\s+(.+?)\\??$");

    private static final Pattern DOES_RELATE = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+relat(?:e|es|ion)?\\s+to\\s+(.+?)\\??$");

    private static final Pattern ASSOCIATED = Pattern.compile(
            "(?i)(?:how\\s+(?:is|are)\\s+)?(.+?)\\s+associated\\s+with\\s+(.+?)\\??$");

    private static final Pattern LINK_BETWEEN = Pattern.compile(
            "(?i)link\\s+between\\s+(.+?)\\s+and\\s+(.+?)\\??$");

    private static final Pattern INFLUENCE = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+(?:increase|decrease|attract)\\s+(.+?)\\??$");

    private static final Pattern VERSUS = Pattern.compile(
            "(?i)(.+?)\\s+versus\\s+(.+?)\\??$");

    private static final Pattern LINKED_TO = Pattern.compile(
            "(?i)(.+?)\\s+linked to\\s+(.+?)\\??$");

    private static final Pattern IMPACT_ON = Pattern.compile(
            "(?i)(.+?)\\s+impact on\\s+(.+?)\\??$");

    private static final Pattern INFLUENCE_ON = Pattern.compile(
            "(?i)(.+?)\\s+influence on\\s+(.+?)\\??$");

    private static final Pattern CONNECTION_BETWEEN = Pattern.compile(
            "(?i)(?:any\\s+)?connection between\\s+(.+?)\\s+and\\s+(.+?)\\??$");

    private static final Pattern GROW_WITH = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+grow with\\s+(.+?)\\??$");

    private static final Pattern SCALE_WITH = Pattern.compile(
            "(?i)(?:does|do)\\s+(.+?)\\s+scale with\\s+(.+?)\\??$");

    private static final Pattern CLIMBING_WITH = Pattern.compile(
            "(?i)(.+?)\\s+climbing with\\s+(.+?)\\??$");

    private static final Pattern TIED_TO = Pattern.compile(
            "(?i)(.+?)\\s+tied to\\s+(.+?)\\??$");

    private static final Pattern SWAY_FROM = Pattern.compile(
            "(?i)(.+?)\\s+sway from\\s+(.+?)\\??$");

    private static final Pattern WHAT_DRIVES = Pattern.compile(
            "(?i)what\\s+drives?\\s+(.+?)\\??$");

    private static final String[] TRIGGER_WORDS = {
            "affect", "impact", "influence", "relationship", "correlate", "correlation",
            "driver", "drives", "associated with", "relate to", "related to", "relates to",
            "link between", " linked to ", " impact on ", " influence on ", " sway from ",
            " connection between ", " grow with ", " scale with ", " climbing with ", " tied to ",
            " versus ", " increase ", " decrease ", " hurt ", " attract "
    };

    public boolean matches(String question) {
        if (question == null || question.isBlank()) return false;
        if (slotMatch(question) != null) return true;
        String lower = question.toLowerCase(Locale.ROOT);
        for (String trigger : TRIGGER_WORDS) {
            if (lower.contains(trigger)) return true;
        }
        return false;
    }

    public boolean isRelationship(AnalyticalIntentType intent) {
        return intent == AnalyticalIntentType.RELATIONSHIP;
    }

    public boolean isRelationship(AnalyticalRelationship relationship) {
        return relationship == AnalyticalRelationship.METRIC_RELATIONSHIP;
    }

    /**
     * Returns [sourcePhrase, targetPhrase] when a slot pattern matches, else null.
     */
    public String[] slotPhrases(String question) {
        SlotMatch match = slotMatch(question);
        return match != null ? new String[] { match.source(), match.target() } : null;
    }

    private SlotMatch slotMatch(String question) {
        String q = question.trim();
        Matcher affect = AFFECT.matcher(q);
        if (affect.find()) return new SlotMatch(affect.group(1).trim(), affect.group(2).trim());
        Matcher doesAffect = DOES_AFFECT.matcher(q);
        if (doesAffect.find()) return new SlotMatch(doesAffect.group(1).trim(), doesAffect.group(2).trim());
        Matcher corr = CORRELATE.matcher(q);
        if (corr.find()) return new SlotMatch(corr.group(1).trim(), corr.group(2).trim());
        Matcher relate = RELATE.matcher(q);
        if (relate.find()) return new SlotMatch(relate.group(1).trim(), relate.group(2).trim());
        Matcher doesRelate = DOES_RELATE.matcher(q);
        if (doesRelate.find()) return new SlotMatch(doesRelate.group(1).trim(), doesRelate.group(2).trim());
        Matcher assoc = ASSOCIATED.matcher(q);
        if (assoc.find()) return new SlotMatch(assoc.group(1).trim(), assoc.group(2).trim());
        Matcher link = LINK_BETWEEN.matcher(q);
        if (link.find()) return new SlotMatch(link.group(1).trim(), link.group(2).trim());
        Matcher influence = INFLUENCE.matcher(q);
        if (influence.find()) return new SlotMatch(influence.group(1).trim(), influence.group(2).trim());
        Matcher versus = VERSUS.matcher(q);
        if (versus.find()) return new SlotMatch(versus.group(1).trim(), versus.group(2).trim());
        Matcher linked = LINKED_TO.matcher(q);
        if (linked.find()) return new SlotMatch(linked.group(1).trim(), linked.group(2).trim());
        Matcher impactOn = IMPACT_ON.matcher(q);
        if (impactOn.find()) return new SlotMatch(impactOn.group(1).trim(), impactOn.group(2).trim());
        Matcher influenceOn = INFLUENCE_ON.matcher(q);
        if (influenceOn.find()) return new SlotMatch(influenceOn.group(1).trim(), influenceOn.group(2).trim());
        Matcher connection = CONNECTION_BETWEEN.matcher(q);
        if (connection.find()) return new SlotMatch(connection.group(1).trim(), connection.group(2).trim());
        Matcher growWith = GROW_WITH.matcher(q);
        if (growWith.find()) return new SlotMatch(growWith.group(1).trim(), growWith.group(2).trim());
        Matcher scaleWith = SCALE_WITH.matcher(q);
        if (scaleWith.find()) return new SlotMatch(scaleWith.group(1).trim(), scaleWith.group(2).trim());
        Matcher climbingWith = CLIMBING_WITH.matcher(q);
        if (climbingWith.find()) {
            return new SlotMatch(climbingWith.group(1).trim(), climbingWith.group(2).trim());
        }
        Matcher tiedTo = TIED_TO.matcher(q);
        if (tiedTo.find()) return new SlotMatch(tiedTo.group(1).trim(), tiedTo.group(2).trim());
        Matcher swayFrom = SWAY_FROM.matcher(q);
        if (swayFrom.find()) {
            // Outcome (group1) sways from driver (group2) — swap for outcome-first resolution.
            return new SlotMatch(swayFrom.group(2).trim(), swayFrom.group(1).trim());
        }
        Matcher drives = WHAT_DRIVES.matcher(q);
        if (drives.find()) return new SlotMatch(null, drives.group(1).trim());
        return null;
    }

    private record SlotMatch(String source, String target) {}
}
