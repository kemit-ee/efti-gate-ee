#!/usr/bin/env python3
"""Split efti_full_epics_en.md into docs/epics/theme_N_en.md and docs/epics/epic_N_en.md files."""

import os
import re

SRC = os.path.join(os.path.dirname(__file__), "efti_full_epics_en.md")
OUT = os.path.join(os.path.dirname(__file__), "epics")
os.makedirs(OUT, exist_ok=True)

# References to inject per epic (keyed by epic number)
# Format: list of (anchor_text, relative_path_from_epics_dir, description)
RA = "../architecture/eFTI-Gate-Reference-Architecture.md"

EPIC_REFS = {
    1:  [("Permissions Matrix", "../specs/permissions-matrix.md", "Complete authorization model and role-based access control specification"),
         ("RA §1 System Actors", f"{RA}#1-system-actors--components", "Actor roles and access channels"),
         ("RA §8.1 Security Layers", f"{RA}#81-security-layers", "Authentication and authorization architecture")],
    2:  [("Permissions Matrix", "../specs/permissions-matrix.md", "Authentication flow and authorization checks"),
         ("RA §8.1 Security Layers", f"{RA}#81-security-layers", "TARA/OIDC, JWT, and mTLS authentication architecture"),
         ("RA §8.2 GDPR Compliance", f"{RA}#82-gdpr-compliance", "GDPR Article 30 requirements")],
    3:  [("DB Schema", "../specs/db/README.md", "Database schema for identifiers and consignments"),
         ("XSD", "../../xsd/consignment-identifier.xsd", "Identifier XML schema"),
         ("RA §2.1 UIL", f"{RA}#21-uil-unique-identifier-for-loading", "UIL structure and identifier registration concepts"),
         ("RA §2.2 Identifiers vs Datasets", f"{RA}#22-identifiers-vs-datasets", "What the gate stores vs what platforms store"),
         ("RA §3 Data Lifecycle", f"{RA}#3-data-lifecycle--ownership", "Identifier lifecycle and ownership rules")],
    4:  [("DB Schema", "../specs/db/README.md", "Database schema for identifier search"),
         ("Permissions Matrix", "../specs/permissions-matrix.md", "Authority access control rules"),
         ("RA §5.1 Identifier Query", f"{RA}#51-identifier-query-cross-border-search", "Cross-border identifier search flow"),
         ("RA §6.1 Gate Responsibilities", f"{RA}#61-gate-responsibilities", "Broadcast-only-when-empty rule")],
    5:  [("DB Schema", "../specs/db/README.md", "Database schema for dataset retrieval"),
         ("Permissions Matrix", "../specs/permissions-matrix.md", "Subset access permissions"),
         ("RA §2.3 Data Subsets", f"{RA}#23-data-subsets", "Subset filtering — gate vs platform responsibility"),
         ("RA §5.2 Dataset Query", f"{RA}#52-dataset-query-request-full-data", "UIL-based dataset retrieval flow"),
         ("RA §5.3 Follow-Up", f"{RA}#53-follow-up-message", "Follow-up message flow")],
    6:  [("DB Schema", "../specs/db/README.md", "Gate registry schema"),
         ("RA §1 System Actors", f"{RA}#1-system-actors--components", "Gate actor roles and registry context")],
    7:  [("DB Schema", "../specs/db/README.md", "Platform registry schema"),
         ("RA §1 System Actors", f"{RA}#1-system-actors--components", "Platform actor roles and registry context")],
    8:  [("DB Schema", "../specs/db/README.md", "Authority registry schema"),
         ("Permissions Matrix", "../specs/permissions-matrix.md", "Authority subset permissions"),
         ("RA §2.3 Data Subsets", f"{RA}#23-data-subsets", "Authority subset assignment model")],
    9:  [("DB Schema", "../specs/db/README.md", "Consignment lifecycle schema"),
         ("RA §3 Data Lifecycle", f"{RA}#3-data-lifecycle--ownership", "CMDS active/inactive/deleted lifecycle"),
         ("RA §6.2 Data Processing Matrix", f"{RA}#62-data-processing-matrix", "What data is stored and where")],
    10: [("eDelivery XSD", "../../xsd/edelivery/gate.xsd", "eDelivery message schema"),
         ("DB Schema", "../specs/db/README.md", "async_responses table schema"),
         ("RA §4 Protocol Architecture", f"{RA}#4-protocol-architecture-generic-envelope--variable-payload", "Generic envelope and AS4 protocol model"),
         ("RA §5.1 Identifier Query", f"{RA}#51-identifier-query-cross-border-search", "Cross-border AS4 message flow")],
    11: [("RA §9.1 Platform API", f"{RA}#91-platform-api", "Platform API endpoints exposed via X-Road"),
         ("RA §1 System Actors", f"{RA}#1-system-actors--components", "EE-specific actor roles (X-Road, ANTS)")],
    12: [("DB Schema", "../specs/db/README.md", "request_ids deduplication table, change_history table"),
         ("RA §7.1 Logical Component Layers", f"{RA}#71-logical-component-layers", "Stateless application layer and shared database architecture")],
    13: [("RA §7.1 Logical Component Layers", f"{RA}#71-logical-component-layers", "Health check endpoints in application layer")],
    14: [("Permissions Matrix", "../specs/permissions-matrix.md", "Role-based access and write-access control"),
         ("Error formats", "../specs/errors.json", "RFC 7807 error catalogue"),
         ("RA §8.1 Security Layers", f"{RA}#81-security-layers", "Full security layer stack: secrets, mTLS, rate limiting, error formats")],
    15: [("Permissions Matrix", "../specs/permissions-matrix.md", "Authorization decisions and audit logging requirements"),
         ("Logging Specification", "../specs/logging-spec.md", "Complete logging format and audit trail specification"),
         ("DB Schema", "../specs/db/README.md", "audit_log table schema"),
         ("RA §8.2 GDPR Compliance", f"{RA}#82-gdpr-compliance", "GDPR Article 30 audit requirements"),
         ("RA §9.3 Audit Logging", f"{RA}#93-audit-logging--compliance-note", "Compliance note on authority query audit")],
    16: [("Logging Specification", "../specs/logging-spec.md", "Complete logging format, ECS schema, and audit trail specification"),
         ("RA §7.1 Logical Component Layers", f"{RA}#71-logical-component-layers", "Observability layer and logging infrastructure")],
    17: [("RA §7.1 Logical Component Layers", f"{RA}#71-logical-component-layers", "Monitoring and metrics in infrastructure layer")],
    18: [],
    19: [("Error formats", "../specs/errors.json", "RFC 7807 error catalogue used across all endpoints"),
         ("RA §9 API Reference", f"{RA}#9-api-reference", "API endpoint reference for versioning and standardisation")],
    20: [],
    21: [("Permissions Matrix", "../specs/permissions-matrix.md", "Authority subset access permissions"),
         ("RA §9.2 Authority API (AAP)", f"{RA}#92-authority-api-aap", "AAP endpoint reference — H2M and M2M interface")],
    22: [("Permissions Matrix", "../specs/permissions-matrix.md", "Admin role capabilities and access control"),
         ("RA §7.1 Logical Component Layers", f"{RA}#71-logical-component-layers", "Admin UI layer in component architecture")],
    23: [("RA §8.1 Security Layers", f"{RA}#81-security-layers", "Authentication architecture for all three flows")],
    24: [("RA §5.1 Identifier Query", f"{RA}#51-identifier-query-cross-border-search", "Identifier search flow diagrams"),
         ("RA §5.2 Dataset Query", f"{RA}#52-dataset-query-request-full-data", "Dataset retrieval flow diagrams")],
    25: [("RA §4 Protocol Architecture", f"{RA}#4-protocol-architecture-generic-envelope--variable-payload", "AS4 envelope and protocol model"),
         ("RA §5.1 Identifier Query", f"{RA}#51-identifier-query-cross-border-search", "Cross-border search flow")],
}

# Theme-to-epics mapping
THEME_EPICS = {
    1: [1, 2, 23],
    2: [3, 4, 5, 24],
    3: [6, 7, 8, 9],
    4: [10, 11, 25],
    5: [12, 13],
    6: [14, 15],
    7: [16, 17],
    8: [18, 19, 20],
    9: [21, 22],
}

with open(SRC, encoding="utf-8") as f:
    content = f.read()

lines = content.split("\n")

# Parse structure: find THEME and EPIC boundaries
sections = []  # (type, number, title, start_line)
for i, line in enumerate(lines):
    m = re.match(r'^## THEME (\d+) — (.+)$', line)
    if m:
        sections.append(("theme", int(m.group(1)), m.group(2).strip(), i))
        continue
    m = re.match(r'^### EPIC (\d+) — (.+)$', line)
    if m:
        sections.append(("epic", int(m.group(1)), m.group(2).strip(), i))

# Find end lines
def get_end(idx, sections, lines):
    if idx + 1 < len(sections):
        return sections[idx + 1][3]
    return len(lines)

# Header block (before first theme)
header_end = sections[0][3] if sections else len(lines)
header_block = "\n".join(lines[:header_end]).rstrip()

# Footer block (after last section)
last_end = get_end(len(sections) - 1, sections, lines)
footer_block = "\n".join(lines[last_end:]).strip()

def inject_refs(epic_num, block_lines):
    """Inject **References:** block after the SO THAT line if refs exist and not already present."""
    refs = EPIC_REFS.get(epic_num, [])
    if not refs:
        return block_lines

    # Check if References already present
    full = "\n".join(block_lines)
    if "**Reference" in full:
        return block_lines  # already has references

    # Find insertion point: after SO THAT line, before #### or blank+####
    insert_at = None
    for i, line in enumerate(block_lines):
        if line.startswith("**SO THAT**"):
            insert_at = i + 1
            break

    if insert_at is None:
        return block_lines

    ref_lines = [""]
    if len(refs) == 1:
        anchor, path, desc = refs[0]
        ref_lines.append(f"**Reference:** [{anchor}]({path}) — {desc}")
    else:
        ref_lines.append("**References:**")
        for anchor, path, desc in refs:
            ref_lines.append(f"- [{anchor}]({path}) — {desc}")

    return block_lines[:insert_at] + ref_lines + block_lines[insert_at:]


def make_epic_file(epic_num, epic_title, epic_lines, theme_num):
    """Build content for a standalone epic file."""
    ref_path = f"theme_{theme_num}_en.md"
    header = f"# EPIC {epic_num} — {epic_title}\n\n> Part of [Theme {theme_num}]({ref_path})\n"
    body = "\n".join(inject_refs(epic_num, epic_lines[1:]))  # skip the ### EPIC N line itself
    return header + "\n" + body.lstrip("\n")


def make_theme_file(theme_num, theme_title, theme_lines, epic_entries):
    """Build content for a standalone theme file with links to epics."""
    # Build epic links section
    epic_links = "\n".join(
        f"- [EPIC {en} — {et}](epic_{en}_en.md)"
        for en, et in epic_entries
    )
    # Find insertion point after "Theme done when" block or after business value
    full_theme = "\n".join(theme_lines)
    # Insert epic index before first ### EPIC heading
    epic_heading_pos = full_theme.find("\n### EPIC ")
    if epic_heading_pos == -1:
        # No epics inline — just append
        body = full_theme + f"\n\n## Epics\n\n{epic_links}\n"
    else:
        before = full_theme[:epic_heading_pos]
        after = full_theme[epic_heading_pos:]
        body = before + f"\n\n## Epics\n\n{epic_links}\n" + after

    return f"# THEME {theme_num} — {theme_title}\n\n" + body.lstrip("## THEME").lstrip().split("\n", 1)[-1]


# Build index of epic titles
epic_title_map = {s[1]: s[2] for s in sections if s[0] == "epic"}

# Build inverted map: epic -> theme
epic_to_theme = {}
for tn, epics in THEME_EPICS.items():
    for en in epics:
        epic_to_theme[en] = tn

# Extract raw lines per section
section_lines = {}
for idx, (stype, snum, stitle, sstart) in enumerate(sections):
    send = get_end(idx, sections, lines)
    section_lines[(stype, snum)] = lines[sstart:send]

# Write epic files
for stype, snum, stitle, _ in sections:
    if stype != "epic":
        continue
    theme_num = epic_to_theme.get(snum, 0)
    raw = section_lines[("epic", snum)]
    content_out = make_epic_file(snum, stitle, raw, theme_num)
    fname = os.path.join(OUT, f"epic_{snum}_en.md")
    with open(fname, "w", encoding="utf-8") as f:
        f.write(content_out)
    print(f"  Wrote {fname}")

# Write theme files (include inline epic sections → replaced with links + each epic as separate file)
for stype, snum, stitle, sstart in sections:
    if stype != "theme":
        continue
    raw = section_lines[("theme", snum)]
    theme_epic_entries = [(en, epic_title_map[en]) for en in THEME_EPICS.get(snum, []) if en in epic_title_map]
    content_out = make_theme_file(snum, stitle, raw, theme_epic_entries)
    fname = os.path.join(OUT, f"theme_{snum}_en.md")
    with open(fname, "w", encoding="utf-8") as f:
        f.write(content_out)
    print(f"  Wrote {fname}")

# Write index file
index_lines = ["# eFTI Gate — Epics Index\n"]
index_lines.append("> Auto-split from `../efti_full_epics_en.md`. See also [full file](../efti_full_epics_en.md).\n")
for tn in sorted(THEME_EPICS.keys()):
    theme_title = next((s[2] for s in sections if s[0] == "theme" and s[1] == tn), "")
    index_lines.append(f"\n## [THEME {tn} — {theme_title}](theme_{tn}_en.md)\n")
    for en in THEME_EPICS[tn]:
        et = epic_title_map.get(en, "")
        index_lines.append(f"- [EPIC {en} — {et}](epic_{en}_en.md)")

index_lines.append("")
with open(os.path.join(OUT, "README.md"), "w", encoding="utf-8") as f:
    f.write("\n".join(index_lines))
print(f"  Wrote {OUT}/README.md")
print("Done.")
