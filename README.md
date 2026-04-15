# Redmine2Github

A Java CLI tool for migrating Redmine projects to GitHub.  
Safely migrates Wiki, Issues, and Time Entries in **2 phases**.

> 한국어 문서: [README.kr.md](README.kr.md)

## What Gets Migrated

| Redmine | GitHub |
|---------|--------|
| Wiki pages (Textile) | `.md` files (GFM) — repository push |
| Issues | `issues/{id}.md` + `issues.md` index — repository push (default) |
| Issues | GitHub Issues with Labels + Milestones + Assignees (`REDMINE_ISSUE_MD_FETCH=false`) |
| Issue comments / history | Comments section in issue MD (notes + field change history) |
| Versions | Milestones |
| Trackers / Priorities / Statuses / Categories | Labels |
| Wiki attachments | Repository push (`attachments/`) |
| Issue attachments | Repository push (`attachments-issue/`) |
| Time Entries | `time_entries.csv` — repository push |

## 2-Phase Workflow

```
Phase 1: fetch          →  output/ (local files)
                               ↓ review & edit
Phase 2: upload         →  GitHub
```

- **Phase 1 (fetch)**: Collect from Redmine API → Convert Textile → Save locally  
- **Phase 2 (upload)**: Local files → GitHub Issues / file push

## Requirements

- Java 21+
- Gradle 8+ (for building)
- Redmine API Key or Redmine credentials (username/password) — required for Phase 1
- GitHub Personal Access Token (repo scope) — required for Phase 2

## Build

```bash
./gradlew shadowJar
# → build/libs/redmine2github.jar
```

## Configuration

### 1. Environment Variables

| Variable | Phase | Required | Description |
|----------|-------|:--------:|-------------|
| `REDMINE_URL` | fetch | ✅ | Redmine server URL |
| `REDMINE_PROJECT` | fetch | △ | Redmine project identifier (required for single project) |
| `REDMINE_PROJECTS` | fetch | — | Multiple project identifiers, comma-separated (e.g. `proj-a,proj-b`) |
| `REDMINE_API_KEY` | fetch | ※ | Redmine API Key (method 1) |
| `REDMINE_USERNAME` | fetch | ※ | Redmine login ID (method 2) |
| `REDMINE_PASSWORD` | fetch | ※ | Redmine password (method 2) |
| `GITHUB_TOKEN` | upload | ✅ | GitHub Personal Access Token |
| `GITHUB_REPO` | upload | ✅ | Target repository (`owner/repo-name`) |
| `OUTPUT_DIR` | both | — | Output path (default: `./output`) |
| `CACHE_DIR` | fetch | — | Cache path (default: `./cache`) |
| `GITHUB_UPLOAD_METHOD` | upload | — | `API` or `JGIT` (default: `API`) |
| `REQUEST_DELAY_MS` | both | — | Delay between API requests in ms (default: `10`) |
| `REDMINE_ISSUE_MD_FETCH` | fetch/upload | — | Save issues as MD files (default: `true`). `true` → generate `issues/{id}.md` + `issues.md`, upload to repository. `false` → register via GitHub Issues API |

> ※ One of `REDMINE_API_KEY` or `REDMINE_USERNAME`+`REDMINE_PASSWORD` is required  
> △ Not needed when using `--all` or `--project <id>`

```bash
cp .env.example .env
# Edit the .env file
```

### 2. Generate User Mapping File

```bash
java -jar build/libs/redmine2github.jar generate-mapping
# → Generates a user-mapping.yml draft; fill in GitHub usernames manually
```

> If `/users.json` returns 403 (non-admin), the tool falls back to
> `/projects/{project}/memberships.json`. In that case keys are filled with
> **display names** — replace them with actual Redmine **login IDs** before running.

### 3. URL Rewrite Rules (`url-rewrites.yml`) — optional

Replaces legacy URLs (e.g. IP addresses) found in wiki markdown during fetch.

```bash
cp url-rewrites.yml.example url-rewrites.yml
# Edit url-rewrites.yml
```

```yaml
rewrites:
  - old: "http://{IP}/svn"
    new: "http://{domain}/svn"
```

## Usage

### Phase 1: Redmine → Local Fetch

```bash
# Single project (uses REDMINE_PROJECT env var)
./scripts/fetch.sh                       # macOS / Linux
scripts\fetch.bat                        # Windows

# Specify a project directly
./scripts/fetch.sh --project my-project

# Fetch all accessible projects
./scripts/fetch.sh --all
```

Converted files are saved to the `output/` directory. You can review and edit them before uploading.

### Phase 2: Local → GitHub Upload

```bash
# macOS / Linux
./scripts/upload.sh

# Windows
scripts\upload.bat
```

### Combined Run (fetch + upload)

The scripts call the `migrate` CLI command internally.

```bash
./scripts/migrate.sh      # macOS / Linux
scripts\migrate.bat       # Windows
```

### Run Specific Items Only

```bash
./scripts/fetch.sh --only wiki
./scripts/upload.sh --only issues
./scripts/migrate.sh --only time-entries
```

### Key Options

| Option | Description |
|--------|-------------|
| `--only <target>` | Run only `wiki` / `issues` / `time-entries` |
| `--resume` | Resume from the last interruption point |
| `--retry-failed` | Reprocess only items that failed in the previous run |
| `--all` | Fetch all accessible projects (`fetch` only) |
| `--project <id>` | Specify a project to fetch (`fetch` only) |
| `--skip <id,...>` | Projects to exclude when using `--all` (`fetch` only) |

## File Structure

```
.
├── scripts/                # Run scripts
│   ├── fetch.sh / fetch.bat          # Single or bulk (--all) fetch
│   ├── upload.sh / upload.bat
│   └── migrate.sh / migrate.bat      # Calls migrate internally
├── documents/              # Documentation
│   ├── UserManual.md
│   ├── WBS.wiki.md
│   ├── PRD.wiki.md
│   └── github.upload.md
├── .env                    # Environment variables (gitignore)
├── user-mapping.yml        # User mapping (gitignore)
├── label-colors.yml        # Label color config (gitignore)
├── url-rewrites.yml        # URL rewrite rules (gitignore)
├── output/                 # fetch output (gitignore)
│   └── {project}/
│       ├── wiki/
│       ├── attachments/           # Wiki attachments
│       ├── attachments-ext/       # External files downloaded from Redmine URLs
│       ├── attachments-issue/     # Issue attachments
│       ├── issues-json/           # Issue JSON + Label/Milestone definitions
│       ├── issues/                # Issue MD files (REDMINE_ISSUE_MD_FETCH=true)
│       ├── issues.md              # Issue index table
│       └── _migration/
├── cache/                  # API cache (gitignore)
└── migration-state.json    # Progress state (gitignore)
```

## Logs

```bash
tail -f migration.log
```

## GitHub Upload Methods

| Method | Description | Best For |
|--------|-------------|----------|
| `API` (default) | GitHub Contents API, file-by-file upload | Small scale (< 500 files) |
| `JGIT` | git clone → commit → push | Large scale, large files |

See comparison: [documents/github.upload.md](documents/github.upload.md)

## Resume & Idempotency

Completed items are tracked via `migration-state.json`.

```bash
./scripts/fetch.sh --resume      # Resume fetch
./scripts/upload.sh --resume     # Resume upload
./scripts/upload.sh --retry-failed  # Reprocess failed items
```

## Testing

```bash
./gradlew test
```

## Tech Stack

- **Java 21**, Gradle
- **CLI**: picocli 4.7.6
- **HTTP**: OkHttp 4.12.0
- **JSON/YAML**: Jackson 2.17.2
- **GitHub integration**: kohsuke/github-api 1.321, JGit 6.9.0
- **Text conversion**: Regex-based Textile → GFM
- **CSV**: Apache Commons CSV 1.11.0
- **Logging**: SLF4J + Logback

