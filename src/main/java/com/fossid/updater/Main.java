package com.fossid.updater;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

@Command(
    name = "fossidComplianceUpdater",
    mixinStandardHelpOptions = true,
    version = "1.1.0",
    description = {
        "FossID Dependency License/URL Updater & Copyright Updater",
        "",
        "Modes (--getinfo):",
        "  licenseUrl  - Fetch dependency analysis results, fill missing license/URL via AI.",
        "  copyright   - Fetch identified & dependency components, fill missing copyright via AI.",
        ""
    }
)
public class Main implements Callable<Integer> {

    @Option(names = {"--getinfo"},
            required = false,
            defaultValue = "licenseUrl",
            description = "Operation mode: 'licenseUrl' (default) or 'copyright'")
    private String getinfo;

    @Option(names = {"--address"}, required = true, description = "FossID server base URL or api.php endpoint")
    private String address;

    @Option(names = {"--username"}, required = true, description = "FossID username")
    private String username;

    @Option(names = {"--apikey"}, required = false, description = "FossID API key (or env FOSSID_API_KEY)")
    private String apiKey;

    @Option(names = {"--scancode"}, required = true, description = "FossID scan code to analyze")
    private String scanCode;
    // ── Global optional ──────────────────────────────────────────────
    @Option(names = {"--githubpat"}, description = "GitHub Personal Access Token for higher rate limits (or env GITHUB_PAT)")
    private String githubPat;

    // ── AI tool selection ────────────────────────────────────────────
    @Option(names = {"--aitool"}, required = true, description = "AI tool to use: 'gemini', 'gemini_cli' or 'ollama'")
    private String aiTool;

    // Gemini options
    @Option(names = {"--geminimodel"}, description = "Gemini model name (e.g. gemini-2.5-flash)")
    private String geminiModel;

    @Option(names = {"--geminitoken"}, description = "Gemini API key/token (or env GEMINI_TOKEN)")
    private String geminiToken;

    // Ollama options
    @Option(names = {"--ollamaaddress"}, description = "Ollama server address (e.g. http://192.168.0.49:11434)")
    private String ollamaAddress;

    @Option(names = {"--ollamamodel"}, description = "Ollama model name (e.g. qwen3.5:35b)")
    private String ollamaModel;

    @Option(names = {"--ollamatoken"}, description = "Ollama Bearer token (if auth proxy is required, or env OLLAMA_TOKEN)")
    private String ollamaToken;

    @Override
    public Integer call() {
        // ── Setup Logging ────────────────────────────────────────────────
        // Env var fallback for sensitive credentials (avoids exposing secrets in process list)
        if (apiKey    == null || apiKey.isEmpty())    apiKey    = System.getenv("FOSSID_API_KEY");
        if (geminiToken == null || geminiToken.isEmpty()) geminiToken = System.getenv("GEMINI_TOKEN");
        if (ollamaToken == null || ollamaToken.isEmpty()) ollamaToken = System.getenv("OLLAMA_TOKEN");
        if (githubPat == null || githubPat.isEmpty()) githubPat = System.getenv("GITHUB_PAT");

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("--apikey is required (or set env var FOSSID_API_KEY)");
        }

        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmm").format(java.time.LocalDateTime.now());
        String logFileName = timestamp + "_" + getinfo + ".txt";
        
        try (FileOutputStream fos = new FileOutputStream(logFileName);
             DualPrintStream dualOut = new DualPrintStream(System.out, fos);
             DualPrintStream dualErr = new DualPrintStream(System.err, fos)) {
            
            System.setOut(dualOut);
            System.setErr(dualErr);
            
            System.out.println("Logging to: " + logFileName);

            // ── Build AI client ──────────────────────────────────────────────
            AiClient aiClient;
            if ("ollama".equalsIgnoreCase(aiTool)) {
                if (ollamaAddress == null || ollamaModel == null) {
                    throw new IllegalArgumentException(
                            "When using --aitool ollama, both --ollamaaddress and --ollamamodel are required.");
                }
                aiClient = new OllamaClient(ollamaAddress, ollamaModel, ollamaToken);
                System.out.println("[AI] Using Ollama: " + ollamaAddress + " / model=" + ollamaModel);
            } else if ("gemini".equalsIgnoreCase(aiTool)) {
                if (geminiModel == null || geminiToken == null) {
                    throw new IllegalArgumentException(
                            "When using --aitool gemini, both --geminimodel and --geminitoken are required.");
                }
                aiClient = new GeminiClient(geminiModel, geminiToken);
                System.out.println("[AI] Using Gemini model=" + geminiModel);
            } else if ("gemini_cli".equalsIgnoreCase(aiTool)) {
                if (geminiModel != null && geminiToken != null) {
                    aiClient = new GeminiCliClient(geminiModel, geminiToken);
                    System.out.println("[AI] Using Gemini CLI (via 'gemini' command) with provided model=" + geminiModel);
                } else {
                    aiClient = new GeminiCliClient();
                    System.out.println("[AI] Using Gemini CLI (via 'gemini' command) with system default config");
                }
            } else {
                throw new IllegalArgumentException("Invalid --aitool value '" + aiTool + "'. Must be 'gemini', 'gemini_cli' or 'ollama'.");
            }

            FossIdClient fossIdClient = new FossIdClient(address, username, apiKey);

            // ── Dispatch by mode ─────────────────────────────────────────────
            if ("copyright".equalsIgnoreCase(getinfo)) {
                CopyrightUpdaterService service = new CopyrightUpdaterService(fossIdClient, aiClient, githubPat);
                service.run(scanCode);
            } else if ("licenseUrl".equalsIgnoreCase(getinfo)) {
                LicenseUpdaterService service = new LicenseUpdaterService(fossIdClient, aiClient, githubPat);
                service.run(scanCode);
            } else {
                throw new IllegalArgumentException(
                        "Invalid --getinfo value '" + getinfo + "'. Must be 'licenseUrl' or 'copyright'.");
            }

            return 0;
        } catch (Exception e) {
            System.err.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            // Restore original streams just in case (though process is ending)
            System.setOut(System.out);
            System.setErr(System.err);
        }
    }

    /**
     * Helper class to write to two output streams at once.
     */
    private static class DualPrintStream extends java.io.PrintStream {
        private final java.io.OutputStream out2;

        public DualPrintStream(java.io.OutputStream out1, java.io.OutputStream out2) {
            super(out1);
            this.out2 = out2;
        }

        @Override
        public void write(int b) {
            super.write(b);
            try {
                out2.write(b);
                out2.flush();
            } catch (Exception e) {
                // ignore
            }
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            try {
                out2.write(buf, off, len);
                out2.flush();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}

