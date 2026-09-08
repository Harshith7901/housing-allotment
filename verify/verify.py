#!/usr/bin/env python3
"""
Independent verifier for a housing-scheme draw.

This script is deliberately NOT part of the application. It shares no code with the
Java service, imports nothing beyond the Python standard library, and reads only the
files in a published verification bundle. It exists so that an applicant, a newspaper
or a court-appointed expert can answer one question without trusting the authority,
the software vendor, or this repository:

    given the published rules, the published roll and the published seed,
    is the published result the result those inputs actually produce?

Usage:
    python3 verify.py <bundle-directory>

Exit status 0 means every check passed. Anything else means the published result does
not follow from the published inputs, and the script says exactly where it diverges.
"""

import hashlib
import os
import sys
from decimal import Decimal, ROUND_HALF_UP, ROUND_FLOOR


# --------------------------------------------------------------------------- helpers

def sha256_hex(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


class Report:
    def __init__(self):
        self.passed = 0
        self.failed = 0

    def check(self, description, condition, detail=""):
        if condition:
            self.passed += 1
            print("  [PASS] %s" % description)
        else:
            self.failed += 1
            print("  [FAIL] %s" % description)
            if detail:
                for line in str(detail).splitlines()[:12]:
                    print("         %s" % line)

    def section(self, title):
        print("\n== %s %s" % (title, "=" * max(0, 70 - len(title))))


# --------------------------------------------------------------------------- parsing

def parse_directives(text):
    """Parses the shared 'key=value' line format, keeping repeated keys in order."""
    out = []
    for raw in text.split("\n"):
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        out.append((key, value))
    return out


def parse_rules(text):
    rules = {
        "reserved": [],      # (code, label, Decimal percent)
        "horizontal": [],    # (code, label, Decimal percent)
        "residency": ("NONE", 0),
        "waitlistSize": 0,
    }
    for key, value in parse_directives(text):
        if key == "reserved":
            code, label, pct = value.split("|")
            rules["reserved"].append((code, label, Decimal(pct)))
        elif key == "horizontal":
            code, label, pct = value.split("|")
            rules["horizontal"].append((code, label, Decimal(pct)))
        elif key == "residency":
            mode, years = value.split("|")
            rules["residency"] = (mode, int(years))
        elif key in ("totalUnits", "waitlistSize"):
            rules[key] = int(value)
        else:
            rules[key] = value
    return rules


def parse_roll(text):
    roll = {"entries": []}
    for key, value in parse_directives(text):
        if key == "e":
            app_id, cluster, vertical, horizontals, years, prefs = value.split("|")
            roll["entries"].append({
                "id": app_id,
                "cluster": cluster,
                "vertical": vertical,
                "horizontal": set(h for h in horizontals.split(",") if h),
                "years": int(years),
                "prefs": [p for p in prefs.split(",") if p],
            })
        elif key == "entryCount":
            roll[key] = int(value)
        else:
            roll[key] = value
    return roll


def parse_result(text):
    result = {"plan": [], "pools": [], "selections": [], "raw_sel": []}
    for key, value in parse_directives(text):
        if key == "plan":
            parts = value.split("|")
            minima = {}
            for token in parts[2:]:
                code, count = token.split(":")
                minima[code] = int(count)
            result["plan"].append({"code": parts[0], "seats": int(parts[1]), "minima": minima})
        elif key == "pool":
            code, vertical, seats, candidates, unfilled = value.split("|")
            result["pools"].append({
                "code": code, "vertical": vertical, "seats": int(seats),
                "candidates": int(candidates), "unfilled": int(unfilled),
            })
        elif key == "sel":
            result["raw_sel"].append(value)
        elif key == "totalUnits":
            result[key] = int(value)
        else:
            result[key] = value
    return result


# --------------------------------------------------------- re-implemented arithmetic

def largest_remainder(total, percents):
    """Hare apportionment. Ties in the remainder go to the earlier declared entry."""
    exact = []
    seats = []
    remainders = []
    for pct in percents:
        value = (pct * Decimal(total) / Decimal(100)).quantize(Decimal("1.0000000000"),
                                                               rounding=ROUND_HALF_UP)
        floor = value.quantize(Decimal("1"), rounding=ROUND_FLOOR)
        exact.append(value)
        seats.append(int(floor))
        remainders.append(value - floor)
    leftover = total - sum(seats)
    if leftover > 0:
        order = sorted(range(len(percents)), key=lambda i: (-remainders[i], i))
        for i in range(leftover):
            seats[order[i]] += 1
    return seats


def horizontal_minima(rules, seats):
    out = []
    for code, _label, pct in rules["horizontal"]:
        minimum = (pct * Decimal(seats) / Decimal(100)).quantize(Decimal("1"),
                                                                 rounding=ROUND_HALF_UP)
        out.append((code, int(minimum)))
    return out


def seat_plan(rules):
    reserved_total = sum((pct for _c, _l, pct in rules["reserved"]), Decimal(0))
    codes = [rules["openCode"]] + [c for c, _l, _p in rules["reserved"]]
    percents = [Decimal(100) - reserved_total] + [p for _c, _l, p in rules["reserved"]]
    seats = largest_remainder(rules["totalUnits"], percents)
    return [{"code": codes[i], "seats": seats[i], "minima": horizontal_minima(rules, seats[i])}
            for i in range(len(codes))]


def ticket_of(seed_hex, application_id):
    return sha256_hex("ticket/1|%s|%s" % (seed_hex, application_id))


def residency_tier(rules, entry):
    mode, min_years = rules["residency"]
    if mode == "PRIORITY_TIER":
        return 0 if entry["years"] >= min_years else 1
    return 0


def count_satisfying(ids, by_id, code):
    return sum(1 for i in ids if code in by_id[i]["horizontal"])


def needed_for_minimum(app_id, processed, provisional_ids, by_id):
    for code, minimum in processed:
        if minimum <= 0 or code not in by_id[app_id]["horizontal"]:
            continue
        if count_satisfying(provisional_ids, by_id, code) <= minimum:
            return True
    return False


def run_pool(pool_code, vertical_code, seats, candidates, minima, by_id):
    """Mirror of AllocationEngine.runPool, written from the documented rules."""
    ranked = sorted(candidates, key=lambda c: (c["tier"], c["ticket"], c["id"]))
    rank_of = {c["id"]: i + 1 for i, c in enumerate(ranked)}

    fill = min(max(seats, 0), len(ranked))
    provisional = {}                      # rank -> application id
    for i in range(fill):
        provisional[i + 1] = ranked[i]["id"]
    selected_reason = {ranked[i]["id"]: "SELECTED_ON_MERIT" for i in range(fill)}

    displaced = []
    processed = []
    for code, minimum in minima:
        processed.append((code, minimum))
        if minimum <= 0:
            continue
        ids = list(provisional.values())
        have = count_satisfying(ids, by_id, code)
        if have >= minimum:
            continue
        shortfall = minimum - have
        inside = set(provisional.values())
        outsiders = [c["id"] for c in ranked
                     if c["id"] not in inside and code in by_id[c["id"]]["horizontal"]]
        promoted = 0
        for promote_id in outsiders:
            if promoted >= shortfall:
                break
            victim = None
            for rank in sorted(provisional.keys(), reverse=True):
                candidate = provisional[rank]
                if code in by_id[candidate]["horizontal"]:
                    continue
                if needed_for_minimum(candidate, processed, list(provisional.values()), by_id):
                    continue
                victim = candidate
                break
            if victim is None:
                break
            del provisional[rank_of[victim]]
            selected_reason.pop(victim, None)
            displaced.append(victim)
            provisional[rank_of[promote_id]] = promote_id
            selected_reason[promote_id] = "SELECTED_VIA_HORIZONTAL_MINIMUM"
            if promote_id in displaced:
                displaced.remove(promote_id)
            promoted += 1

    return {
        "code": pool_code,
        "vertical": vertical_code,
        "seats": seats,
        "ranking": ranked,
        "rank_of": rank_of,
        "selected": [provisional[r] for r in sorted(provisional.keys())],
        "reasons": selected_reason,
        "displaced": displaced,
        "unfilled": seats - fill,
    }


def recompute(rules, roll, seed_hex):
    """Runs the whole draw and returns the canonical 'sel=' lines it implies."""
    by_id = {e["id"]: e for e in roll["entries"]}
    candidates_all = [{"id": e["id"], "tier": residency_tier(rules, e),
                       "ticket": ticket_of(seed_hex, e["id"])} for e in roll["entries"]]

    plan = {v["code"]: v for v in seat_plan(rules)}

    selected_global = set()
    selected_in_pool = {}
    selection_reason = {}
    displaced_anywhere = set()
    pools = []

    def absorb(pool, override=None):
        pools.append(pool)
        for app_id, reason in pool["reasons"].items():
            selected_global.add(app_id)
            selected_in_pool[app_id] = pool["code"]
            selection_reason[app_id] = override if override else reason
            displaced_anywhere.discard(app_id)
        displaced_anywhere.update(pool["displaced"])

    open_code = rules["openCode"]
    absorb(run_pool(open_code, open_code, plan[open_code]["seats"],
                    candidates_all, plan[open_code]["minima"], by_id))

    reserved_unfilled = 0
    for code, _label, _pct in rules["reserved"]:
        subset = [c for c in candidates_all
                  if by_id[c["id"]]["vertical"] == code and c["id"] not in selected_global]
        pool = run_pool(code, code, plan[code]["seats"], subset, plan[code]["minima"], by_id)
        absorb(pool)
        reserved_unfilled += pool["unfilled"]

    if reserved_unfilled > 0 and rules.get("lapse") == "LAPSE_TO_OPEN":
        subset = [c for c in candidates_all if c["id"] not in selected_global]
        pool = run_pool("OPEN_LAPSED", open_code, reserved_unfilled, subset,
                        horizontal_minima(rules, reserved_unfilled), by_id)
        absorb(pool, override="SELECTED_IN_LAPSED_OPEN_POOL")

    best_position = {}
    best_pool = {}
    best_rank = {}
    for pool in pools:
        position = 0
        for candidate in pool["ranking"]:
            app_id = candidate["id"]
            if app_id in selected_global:
                continue
            position += 1
            if app_id not in best_position or position < best_position[app_id]:
                best_position[app_id] = position
                best_pool[app_id] = pool["code"]
                best_rank[app_id] = pool["rank_of"][app_id]

    pool_seats = {p["code"]: p["seats"] for p in pools}
    tickets = {c["id"]: c["ticket"] for c in candidates_all}
    tiers = {c["id"]: c["tier"] for c in candidates_all}

    lines = []
    for entry in roll["entries"]:
        app_id = entry["id"]
        if app_id in selected_global:
            pool_code = selected_in_pool[app_id]
            pool = next(p for p in pools if p["code"] == pool_code)
            lines.append("%s|SELECTED|%s|%s|%d|%d|-|%s" % (
                app_id, pool_code, tickets[app_id], tiers[app_id],
                pool["rank_of"][app_id], selection_reason[app_id]))
        else:
            pool_code = best_pool.get(app_id, open_code)
            position = best_position.get(app_id, 0)
            rank = best_rank.get(app_id, 0)
            seats = pool_seats.get(pool_code, 0)
            waitlisted = 0 < position <= rules["waitlistSize"]
            if app_id in displaced_anywhere:
                reason = "DISPLACED_BY_HORIZONTAL_MINIMUM"
            elif seats == 0:
                reason = "NOT_SELECTED_NO_SEATS_IN_POOL"
            elif waitlisted:
                reason = "WAITLISTED_BEHIND_SELECTED"
            else:
                reason = "NOT_SELECTED_RANK_BELOW_SEATS"
            lines.append("%s|%s|%s|%s|%d|%d|%s|%s" % (
                app_id, "WAITLISTED" if waitlisted else "NOT_SELECTED", pool_code,
                tickets[app_id], tiers[app_id], rank,
                str(position) if waitlisted else "-", reason))

    lines.sort(key=lambda line: line.split("|")[0])
    return lines, pools, plan


# --------------------------------------------------------------------------- driver

def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    bundle = argv[1]
    for name in ("rules.txt", "roll.txt", "seed.txt", "result.txt", "MANIFEST.txt"):
        if not os.path.exists(os.path.join(bundle, name)):
            print("bundle is incomplete: %s is missing" % name)
            return 2

    rules_txt = read(os.path.join(bundle, "rules.txt"))
    roll_txt = read(os.path.join(bundle, "roll.txt"))
    seed_txt = read(os.path.join(bundle, "seed.txt"))
    result_txt = read(os.path.join(bundle, "result.txt"))
    manifest_txt = read(os.path.join(bundle, "MANIFEST.txt"))

    manifest = {}
    manifest_files = []
    for key, value in parse_directives(manifest_txt):
        if key == "file":
            name, size, digest = value.split("|")
            manifest_files.append((name, int(size), digest))
        else:
            manifest[key] = value

    report = Report()

    report.section("File integrity")
    for name, size, digest in manifest_files:
        content = read(os.path.join(bundle, name))
        actual = sha256_hex(content)
        report.check("%s matches the digest in MANIFEST.txt" % name, actual == digest,
                     "manifest %s\nactual   %s" % (digest, actual))
        report.check("%s matches the byte count in MANIFEST.txt" % name,
                     len(content.encode("utf-8")) == size)

    report.section("Published hashes")
    report.check("ruleSetHash is the SHA-256 of rules.txt",
                 sha256_hex(rules_txt) == manifest.get("ruleSetHash"))
    report.check("rollHash is the SHA-256 of roll.txt",
                 sha256_hex(roll_txt) == manifest.get("rollHash"))
    report.check("resultHash is the SHA-256 of result.txt",
                 sha256_hex(result_txt) == manifest.get("resultHash"))

    rules = parse_rules(rules_txt)
    roll = parse_roll(roll_txt)
    result = parse_result(result_txt)
    seed = dict(parse_directives(seed_txt))

    report.check("the roll was frozen under the rule set in this bundle",
                 roll.get("ruleSetHash") == sha256_hex(rules_txt),
                 "roll says %s" % roll.get("ruleSetHash"))
    report.check("the result was produced from the roll in this bundle",
                 result.get("rollHash") == sha256_hex(roll_txt))
    report.check("the roll declares the number of entries it contains",
                 roll.get("entryCount") == len(roll["entries"]))

    report.section("Commit and reveal")
    report.check("the published commitment is the hash of the revealed nonce",
                 sha256_hex("commit/1|%s" % seed["nonce"]) == seed["commitment"],
                 "the authority may have substituted the nonce after committing")
    derived = sha256_hex("seed/1|%s|%s|%s" % (seed["rollHash"], seed["publicEntropy"], seed["nonce"]))
    report.check("the seed is derived from the roll hash, the public entropy and the nonce",
                 derived == seed["seedHex"], "recomputed %s\npublished  %s" % (derived, seed["seedHex"]))
    report.check("the seed was committed against this roll",
                 seed["rollHash"] == sha256_hex(roll_txt))
    report.check("the result was drawn with this seed", result.get("seedHex") == seed["seedHex"])

    report.section("Apportionment")
    recomputed_plan = {v["code"]: v for v in seat_plan(rules)}
    for published in result["plan"]:
        mine = recomputed_plan.get(published["code"])
        report.check("category %s is apportioned %d seats"
                     % (published["code"], published["seats"]),
                     mine is not None and mine["seats"] == published["seats"],
                     "recomputed %s" % (mine["seats"] if mine else "missing"))
        for code, minimum in (mine["minima"] if mine else []):
            report.check("  %s minimum for %s is %d" % (published["code"], code, minimum),
                         published["minima"].get(code, 0) == minimum)
    report.check("apportioned seats sum to the published inventory",
                 sum(v["seats"] for v in recomputed_plan.values()) == rules["totalUnits"])

    report.section("The draw, recomputed from scratch")
    lines, pools, _plan = recompute(rules, roll, seed["seedHex"])
    published = sorted(result["raw_sel"], key=lambda line: line.split("|")[0])

    report.check("the recomputed draw covers every applicant on the roll",
                 len(lines) == len(roll["entries"]))
    report.check("the published result covers every applicant on the roll",
                 len(published) == len(roll["entries"]),
                 "published %d lines for %d entries" % (len(published), len(roll["entries"])))

    mismatches = [(a, b) for a, b in zip(published, lines) if a != b]
    report.check("every applicant's outcome, pool, ticket, rank and reason matches",
                 not mismatches and len(published) == len(lines),
                 "\n".join("published: %s\nrecomputed: %s" % (a, b) for a, b in mismatches[:4]))

    for published_pool in result["pools"]:
        mine = next((p for p in pools if p["code"] == published_pool["code"]), None)
        report.check("pool %s: %d candidates, %d selected, %d unfilled"
                     % (published_pool["code"], published_pool["candidates"],
                        published_pool["seats"] - published_pool["unfilled"],
                        published_pool["unfilled"]),
                     mine is not None
                     and len(mine["ranking"]) == published_pool["candidates"]
                     and mine["unfilled"] == published_pool["unfilled"])

    selected = [line for line in lines if line.split("|")[1] == "SELECTED"]
    report.check("exactly %d households are selected" % rules["totalUnits"],
                 len(selected) == rules["totalUnits"],
                 "recomputed %d" % len(selected))

    report.section("Result")
    print("  scheme        : %s" % manifest.get("schemeCode"))
    print("  draw          : %s executed %s" % (manifest.get("drawId"), manifest.get("executedAt")))
    print("  rule set      : %s" % manifest.get("ruleSetVersion"))
    print("  applicants    : %d" % len(roll["entries"]))
    print("  selected      : %d" % len(selected))
    print("  audit head    : %s" % manifest.get("auditHeadHash"))
    print("  checks passed : %d" % report.passed)
    print("  checks failed : %d" % report.failed)
    if report.failed:
        print("\nVERIFICATION FAILED: the published result does not follow from the published inputs.")
        return 1
    print("\nVERIFIED: the published result is exactly what the published rules, roll and seed produce.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
