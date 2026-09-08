package dev.harshith.housing.core.model;

import dev.harshith.housing.core.util.Hashing;
import dev.harshith.housing.core.util.Text;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical, human-readable text encoding of a {@link Rules.RuleSet}.
 *
 * <p>A rule set has to be hashable, and a hash is only useful if a human can look at
 * exactly the bytes that were hashed and understand them. JSON is a poor fit: key order,
 * whitespace and number formatting are all free variables, so two semantically identical
 * JSON documents hash differently and a canonicalising serialiser becomes load-bearing
 * legal infrastructure.
 *
 * <p>So the hashed form is a fixed-order, one-directive-per-line text document that reads
 * like the gazette notification it is meant to mirror:
 *
 * <pre>
 * version=2026-PHASE-1
 * schemeCode=SCH-2026-01
 * totalUnits=600
 * openCode=OPEN
 * reserved=CAT_A|Category A|15.0000
 * reserved=CAT_B|Category B|7.5000
 * horizontal=WOMEN|Women applicants|30.0000
 * residency=PRIORITY_TIER|3
 * lapse=LAPSE_TO_OPEN
 * waitlistSize=150
 * rulesUri=https://example.gov/schemes/2026-01/rules.pdf
 * </pre>
 *
 * <p>Round-tripping is covered by tests; the encoding is stable across JVMs and locales
 * because every field goes through {@link Text}.
 */
public final class RulesCodec {

    private RulesCodec() {
    }

    public static String encode(Rules.RuleSet rs) {
        StringBuilder sb = new StringBuilder();
        sb.append("version=").append(rs.version()).append('\n');
        sb.append("schemeCode=").append(rs.schemeCode()).append('\n');
        sb.append("totalUnits=").append(rs.totalUnits()).append('\n');
        sb.append("openCode=").append(rs.openCode()).append('\n');
        for (Rules.ReservedQuota q : rs.reservedQuotas()) {
            sb.append("reserved=").append(q.code()).append('|')
              .append(q.label()).append('|')
              .append(Text.percent(q.percent())).append('\n');
        }
        for (Rules.HorizontalQuota q : rs.horizontalQuotas()) {
            sb.append("horizontal=").append(q.code()).append('|')
              .append(q.label()).append('|')
              .append(Text.percent(q.percent())).append('\n');
        }
        sb.append("residency=").append(rs.residency().mode()).append('|')
          .append(rs.residency().minYears()).append('\n');
        sb.append("lapse=").append(rs.lapsePolicy()).append('\n');
        sb.append("waitlistSize=").append(rs.waitlistSize()).append('\n');
        sb.append("rulesUri=").append(rs.publishedRulesUri()).append('\n');
        return sb.toString();
    }

    public static String hash(Rules.RuleSet rs) {
        return Hashing.sha256Hex(encode(rs));
    }

    public static Rules.RuleSet decode(String encoded) {
        String version = null;
        String schemeCode = null;
        int totalUnits = 0;
        String openCode = null;
        List<Rules.ReservedQuota> reserved = new ArrayList<>();
        List<Rules.HorizontalQuota> horizontal = new ArrayList<>();
        Rules.ResidencyRule residency = Rules.ResidencyRule.none();
        Rules.LapsePolicy lapse = Rules.LapsePolicy.LAPSE_TO_OPEN;
        int waitlistSize = 0;
        String rulesUri = "";

        int lineNo = 0;
        for (String rawLine : encoded.split("\n")) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("malformed rule set line " + lineNo + ": " + line);
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "version" -> version = value;
                case "schemeCode" -> schemeCode = value;
                case "totalUnits" -> totalUnits = parseInt(value, lineNo);
                case "openCode" -> openCode = value;
                case "reserved" -> {
                    String[] p = splitExact(value, 3, lineNo);
                    reserved.add(new Rules.ReservedQuota(p[0], p[1], new BigDecimal(p[2])));
                }
                case "horizontal" -> {
                    String[] p = splitExact(value, 3, lineNo);
                    horizontal.add(new Rules.HorizontalQuota(p[0], p[1], new BigDecimal(p[2])));
                }
                case "residency" -> {
                    String[] p = splitExact(value, 2, lineNo);
                    residency = new Rules.ResidencyRule(
                            Rules.ResidencyMode.valueOf(p[0]), parseInt(p[1], lineNo));
                }
                case "lapse" -> lapse = Rules.LapsePolicy.valueOf(value);
                case "waitlistSize" -> waitlistSize = parseInt(value, lineNo);
                case "rulesUri" -> rulesUri = value;
                default -> throw new IllegalArgumentException(
                        "unknown rule set directive on line " + lineNo + ": " + key);
            }
        }
        return new Rules.RuleSet(version, schemeCode, totalUnits, openCode,
                reserved, horizontal, residency, lapse, waitlistSize, rulesUri);
    }

    private static String[] splitExact(String value, int expected, int lineNo) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " pipe-separated fields on line " + lineNo + ": " + value);
        }
        return parts;
    }

    private static int parseInt(String value, int lineNo) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected an integer on line " + lineNo + ": " + value);
        }
    }
}
