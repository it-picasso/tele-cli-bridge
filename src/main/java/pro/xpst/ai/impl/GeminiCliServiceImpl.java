package pro.xpst.ai.impl;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@Scope("prototype")
public class GeminiCliServiceImpl extends AbstractCliService {

    private final String tools;

    public GeminiCliServiceImpl(String binary,
                                String workdirPath,
                                String defaultModel,
                                int expirationMinutes,
                                long timeoutSeconds,
                                String tools) {
        super(binary, workdirPath, defaultModel, expirationMinutes, timeoutSeconds);
        this.tools = tools;

        if (tools != null && !tools.isBlank()) {
            Path geminiMd = this.sandboxDir.toPath().resolve("GEMINI.md");
            if (!Files.isRegularFile(geminiMd)) {
                throw new IllegalStateException("Sandbox seeding did not produce " + geminiMd
                        + " — cannot append tool authorization clause");
            }
            try {
                String base = Files.readString(geminiMd, StandardCharsets.UTF_8);
                Files.writeString(geminiMd, applyToolPolicy(base, tools), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to augment sandbox GEMINI.md with tool authorization", e);
            }
        }
    }

    @Override
    protected String providerName() {
        return "gemini";
    }

    @Override
    protected List<String> buildArgv(String serializedPrompt) {
        List<String> argv = new ArrayList<>();
        argv.add(this.binary);
        argv.add("-m");
        argv.add(this.currentModel);
        argv.add("--approval-mode");
        argv.add("plan");
        argv.add("--skip-trust");
        argv.add("-o");
        argv.add("text");
        if (this.tools != null && !this.tools.isBlank()) {
            for (String tool : this.tools.split(",")) {
                String t = tool.trim();
                if (!t.isEmpty()) {
                    argv.add("--allowed-tools");
                    argv.add(t);
                }
            }
        }
        argv.add("-p");
        argv.add(serializedPrompt);
        return argv;
    }
}
