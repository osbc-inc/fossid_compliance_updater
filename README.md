# FossID License/URL & Copyright Updater

This Command Line Interface (CLI) tool interacts with the FossID API and AI models (Gemini or Ollama) to automatically fill in missing information for components in your FossID scans.

It supports two main operations:
1.  **License & URL Updater (`licenseUrl`)**: Analyzes generic dependencies. If a component is missing its SPDX `license_identifier` or official `url`, the tool queries the selected AI model using the package manager, name, and version to find the missing data and updates it back to FossID.
2.  **Copyright Updater (`copyright`)**: Processes both identified scan components and dependency components. If a component's detailed information is missing a `copyright` notice, it asks the AI model to generate the standard copyright notice and updates the component in FossID.

## Prerequisites

*   **Java 8 or higher**: The project is configured to target Java 1.8.
*   **Maven**: To build the project.
*   **FossID API Access**: An active FossID instance with your `username` and `API key`.
*   **AI API Access**:
    *   **Gemini**: A valid Google Gemini API key.
    *   **Ollama**: A running Ollama server (local or remote). Auth tokens are supported if your Ollama instance is behind a proxy.

## Network Requirements (Firewall Allowlist)

The tool makes outbound HTTPS (port 443) connections to the following domains. In corporate environments with egress filtering, these must be explicitly allowed.

### Always Required

| Domain | Purpose |
|--------|---------|
| Your FossID server | Read scan data & write results (user-specified) |
| `api.github.com` | License & copyright lookup from GitHub repositories |

### Required by AI Tool (one of the following)

| Domain | Condition |
|--------|-----------|
| `generativelanguage.googleapis.com` | `--aitool gemini` |
| Your Ollama server | `--aitool ollama` (user-specified) |
| _(none, local PowerShell call)_ | `--aitool gemini_cli` |

### Required by Package Registry (only contacted if the scan contains that package type)

| Domain | Registry | Package Managers |
|--------|----------|-----------------|
| `registry.npmjs.org` | NPM | npm, pnpm, yarn, bower |
| `repo1.maven.org` | Maven Central | maven, gradle, ivy, kotlin |
| `pypi.org` | PyPI | pip, pipenv, poetry, hatch |
| `crates.io` | Crates.io | rust, cargo |
| `rubygems.org` | RubyGems | gem, bundler |
| `api.nuget.org` | NuGet | dotnet, nuget |
| `pkg.go.dev` | Go Packages | godep, gomod, go |
| `repo.packagist.org` | Packagist | composer |
| `cocoapods.org` | CocoaPods | cocoapod, carthage |
| `hex.pm` | Hex | elixir |
| `hackage.haskell.org` | Hackage | haskell |
| `pub.dev` | Pub | dart, flutter |

> **Tip:** If your environment only uses a subset of package managers (e.g., Maven and NPM only), you only need to open the corresponding registry domains.

---

## Build Instructions

To build the executable, run the following Maven command in the root directory:

```bash
mvn clean package
```

This will create an executable "fat" JAR file (which includes all necessary dependencies like `picocli` and `gson`) located at:
`target/checkComplianceInfo.jar`

## Usage

The tool is executed via the command line using `java -jar`. 

### Required Global Arguments

Regardless of the operation mode or AI tool chosen, you must provide your FossID instance details:

*   `--address`: FossID server base URL (e.g., `https://fossid.example.com` or `https://fossid.example.com/api.php`)
*   `--username`: Your FossID username
*   `--apikey`: Your FossID API key (or env `FOSSID_API_KEY`)
*   `--scancode`: The specific scan code to analyze
*   `--aitool`: The AI engine to use. Must be either `gemini`, `gemini_cli`, or `ollama`.

### Optional Global Arguments (Modes)

*   `--getinfo`: The mode of operation. 
    *   `licenseUrl` (Default): Fills missing licenses and URLs.
    *   `copyright`: Fills missing copyright notices.
*   `--githubpat`: (Optional) A GitHub Personal Access Token (or env `GITHUB_PAT`). When processing GitHub packages (`github.com/owner/repo`), the tool directly queries the GitHub REST API to verify the `SPDX license identifier` and extract the `copyright` from the `LICENSE` file. Providing a PAT increases the GitHub API rate limit from 60 requests/hour to 5,000 requests/hour. Highly recommended for large scans!

### Core Logic (Lookup Priority)

To maximize accuracy and eliminate AI hallucinations, the tool follows a strict lookup priority:
1. **GitHub API Lookup:** If the component has a GitHub URL (or one is discovered), the tool queries the GitHub REST API (`/repos/{owner}/{repo}`) for the `SPDX license identifier` and `copyright` from the `LICENSE` file.
2. **Registry API Lookup:** If GitHub data is unavailable, the tool queries official package manager registries (NPM, Maven, PyPI, etc.) to find the homepage URL, license, and **copyright holder**.
   *   **Maven Package Enhancement**: When querying Maven packages, the tool traces up to 5 levels of parent POM to accurately find inherited licenses, URLs, and descriptions. It also automatically converts the `groupId/artifactId` format to the `groupId:artifactId` format, which yields a higher AI recognition rate.
3. **SPDX License Normalization:** If the license information retrieved from GitHub or a Registry is not a standard SPDX Identifier (e.g., "Apache License 2.0"), it is automatically normalized to a standard SPDX ID (e.g., "Apache-2.0") via AI.
4. **AI Fallback & Discovery:** If both fail, the tool asks the AI (Gemini/Ollama) to identify the missing information and the official repository URL.
   *   **AI Prompt Enhancement**: A precision prompt including an expert persona and detailed guide is applied to provide accuracy comparable to the web version of Gemini.
   *   **API Quota Exceeded (429) Detection**: If an API quota exceeded error (`RESOURCE_EXHAUSTED`) occurs while using Gemini CLI, the execution is immediately halted and a guidance message is displayed.
5. **Validation:** If AI suggests a new GitHub URL, the tool re-runs the GitHub API check on that URL for final verification.

*All updates log the exact source of data (e.g., `[Source: Maven Central -> GitHub API]`) and any reference URLs used. License normalization events are also logged.*

### Supported Registries

The tool supports automatic metadata lookup for the following package managers:
- **NPM** (npm, pnpm, yarn, bower)
- **Maven** (maven, gradle, ivy, kotlin)
- **PyPI** (pip, pipenv, poetry, hatch)
- **Crates.io** (rust, cargo)
- **RubyGems** (gem, bundler)
- **NuGet** (dotnet, nuget)
- **Go** (godep, gomod, go) via `pkg.go.dev`
- **Packagist** (composer)
- **CocoaPods** (cocoapod, carthage)
- **Hex.pm** (elixir)
- **Hackage** (haskell)
- **Pub.dev** (dart, flutter)

## Logging

Every execution automatically generates a log file in the current directory containing the full console output. Filenames are timestamped:
- `YYMMDDHHMM_licenseUrl.txt`
- `YYMMDDHHMM_copyright.txt`

---

## AI-Specific Arguments

**When using `--aitool gemini`:**
*   `--geminimodel`: (Required) The Gemini model name (e.g., `gemini-2.5-flash`).
*   `--geminitoken`: (Required) Your Gemini API key (or env `GEMINI_TOKEN`).

**When using `--aitool gemini_cli`:**
*   Requires a PowerShell profile function or global executable named `gemini` available in your system path. The tool will invoke it via `powershell.exe -Command gemini "prompt"`. 
*   `--geminimodel` and `--geminitoken`: (Optional) These can be provided for logging purposes or if your local `gemini` command supports passing them as arguments. By default, it delegates to your local CLI configuration.

**When using `--aitool ollama`:**
*   `--ollamaaddress`: (Required) The address where Ollama is hosted (e.g., `http://ollamaaddress`).
*   `--ollamamodel`: (Required) The Ollama model name (e.g., `qwen3.5:35b` or `llama3`).
*   `--ollamatoken`: (Optional) A Bearer token, if your Ollama instance requires authorization (or env `OLLAMA_TOKEN`).

---

## Examples

### 1. Update License and URL using Gemini (Default mode)

```bash
java -jar target/checkComplianceInfo.jar \
  --address https://fossid.example.com/api.php \
  --username myuser \
  --apikey myAPIkey \
  --scancode my_scan_code_123 \
  --aitool gemini \
  --geminimodel gemini-2.5-flash \
  --geminitoken YOUR_GEMINI_TOKEN
```
*(Note: `--getinfo licenseUrl` is the default and does not strictly need to be provided).*

### 2. Update License and URL using Ollama

```bash
java -jar target/checkComplianceInfo.jar \
  --getinfo licenseUrl \
  --address https://fossid.example.com/api.php \
  --username myuser \
  --apikey myAPIkey \
  --scancode my_scan_code_123 \
  --aitool ollama \
  --ollamaaddress http://ollamaaddress \
  --ollamamodel qwen3.5:35b \
  --ollamatoken YOUR_PROXY_TOKEN_IF_NEEDED
```

### 3. Update Copyright information using Gemini

```bash
java -jar target/checkComplianceInfo.jar \
  --getinfo copyright \
  --address https://fossid.example.com/api.php \
  --username myuser \
  --apikey myAPIkey \
  --scancode my_scan_code_123 \
  --aitool gemini \
  --geminimodel gemini-2.5-flash \
  --geminitoken YOUR_GEMINI_TOKEN
```

### 4. Update Copyright information using Ollama

```bash
java -jar target/checkComplianceInfo.jar \
  --getinfo copyright \
  --address https://fossid.example.com/api.php \
  --username myuser \
  --apikey myAPIkey \
  --scancode my_scan_code_123 \
  --aitool ollama \
  --ollamaaddress http://ollamaaddress \
  --ollamamodel qwen3.5:35b
```

### 5. Update using local Gemini CLI function

```bash
java -jar target/checkComplianceInfo.jar \
  --getinfo copyright \
  --address https://fossid.example.com/api.php \
  --username myuser \
  --apikey myAPIkey \
  --scancode my_scan_code_123 \
  --aitool gemini_cli
```

## Environment Variables

To avoid exposing sensitive credentials in the process list (visible to other OS users via `ps`, Task Manager, etc.), all secret arguments support environment variable fallback.

| Environment Variable | CLI Argument    | Description                        |
|---------------------|-----------------|------------------------------------|
| `FOSSID_API_KEY`    | `--apikey`      | FossID API key                     |
| `GEMINI_TOKEN`      | `--geminitoken` | Google Gemini API key              |
| `OLLAMA_TOKEN`      | `--ollamatoken` | Ollama Bearer token (proxy auth)   |
| `GITHUB_PAT`        | `--githubpat`   | GitHub Personal Access Token       |

The CLI argument always takes precedence over the environment variable when both are provided.

**Example (PowerShell):**
```powershell
$env:FOSSID_API_KEY = "my-fossid-key"
$env:GEMINI_TOKEN   = "my-gemini-key"
$env:GITHUB_PAT     = "ghp_xxxx"

java -jar target/checkComplianceInfo.jar `
  --address https://fossid.example.com/api.php `
  --username myuser `
  --scancode my_scan_code_123 `
  --aitool gemini `
  --geminimodel gemini-2.5-flash
```

---

## Retry Mechanism

Both the Gemini and Ollama API clients implement an exponential backoff retry mechanism. If the AI service responds with a `503 Service Unavailable` or `429 Too Many Requests` status, the tool will automatically pause and retry up to 3 times before failing that specific query.

---

## License

This project is licensed under the **Apache License, Version 2.0**.
See the [LICENSE](LICENSE) file for the full license text.

```
Copyright 2026 OSBC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
