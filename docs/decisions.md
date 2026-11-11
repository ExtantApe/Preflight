# Decisions

Decisions for this repo, newest first, so later work follows them without asking again.

## 2026-11-08: the upgrade helper lives in Preflight

**Decided by the owner.**

- Leaf Juice's plugin upgrade helper moves into Preflight as a standalone command-line tool, `preflight-upgrade`, released next to the plugin. Server owners who don't use Leaf Juice can then use it too, and Leaf Juice calls the tool instead of keeping its own copy.
- The tool does the whole flow on its own: copy the server, boot the copy on the target Paper jar, read the report, plan, apply with its own restore point, and restore. Leaf Juice may add its own restore point on top, and may pass in a copy it already made (`--copy`).
- It keeps Leaf Juice's safety rules:
  - a yes before applying
  - a stopped server
  - a restore point first
  - nothing offered on a missing or unknown report
  - never-off rules in the same file format

**Choices made while building it,** confirmed by the owner on 2026-11-11 (N166, "Keep all seven (Recommended)", relayed verbatim by Helm):

- **A shared outside database stops the check by default.** `--allow-shared-database` lets it run. Leaf Juice only warned, but the copy would write to the live database.
- **The restore point covers plugin jars only,** because they're all the tool changes. The docs and the `apply` output say to take a full backup before the upgrade itself.
- **Plugins switched off in the copy are never offered for switching off,** and nor is anything that depends on them. They're listed as not checked.
- **"Stopped" means nothing answers on the server's port and every world's `session.lock` can be locked.** If either can't be checked, the answer is no.
- **The tool deletes only copies it made.** It recognises them by a marker file. A `--copy` folder is left for its maker to delete, and leftovers from a killed check are listed but not deleted.
- **The `--paper` jar must be a Paper paperclip jar for 26.1 or newer,** or the check stops before copying.
- **The tool has no dependencies.** It reads and writes JSON itself, so the jar runs on its own and needs no NOTICE.

## 2026-11-08: report.xml carries per-plugin properties

Agreed with Leaf Juice: per-plugin detail goes in a JUnit `<properties>` block in `report.xml`, not a separate JSON file, so CI tools keep reading one valid JUnit file. See [report-format.md](report-format.md).

## 2026-11-07: Paper only, Minecraft 26.1 and up

From the owner: every plugin targets Paper with `paper-plugin.yml`, Minecraft 26.1 and up, and Java 25. There's no 1.21.x or Spigot support. See [26x-audit.md](26x-audit.md).
