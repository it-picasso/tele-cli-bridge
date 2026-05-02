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
public class ClaudeCliServiceImpl extends AbstractCliService {

    private final String effort;
    private final String tools;
    private final String securitySystemPrompt;

    public ClaudeCliServiceImpl(String binary,
                                String workdirPath,
                                String defaultModel,
                                int expirationMinutes,
                                long timeoutSeconds,
                                String effort,
                                String tools) {
        super(binary, workdirPath, defaultModel, expirationMinutes, timeoutSeconds);
        this.effort = effort;
        this.tools = tools;
        Path securityFile = this.workdir.toPath().resolve("CLAUDE.md");
        if (!Files.isRegularFile(securityFile)) {
            throw new IllegalStateException("Required security file missing: " + securityFile);
        }
        try {
            String base = Files.readString(securityFile, StandardCharsets.UTF_8);
            this.securitySystemPrompt = applyToolPolicy(base, tools);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + securityFile, e);
        }
    }

    @Override
    protected String providerName() {
        return "claude";
    }

    @Override
    protected List<String> buildArgv(String serializedPrompt) {
        List<String> argv = new ArrayList<>();
        argv.add(this.binary);
        argv.add("--model");
        argv.add(this.currentModel);
        argv.add("--output-format");
        argv.add("text");
        boolean toolsEnabled = this.tools != null && !this.tools.isBlank();
        if (toolsEnabled) {
            String trimmed = this.tools.trim();
            argv.add("--tools");
            argv.add(trimmed);
            argv.add("--allowed-tools");
            argv.add(trimmed);
        } else {
            argv.add("--permission-mode");
            argv.add("plan");
            argv.add("--tools");
            argv.add("");
        }
        argv.add("--system-prompt");
        argv.add(this.securitySystemPrompt);
        if (this.effort != null && !this.effort.isBlank()) {
            argv.add("--effort");
            argv.add(this.effort.trim());
        }
        argv.add("-p");
        argv.add(serializedPrompt);
        return argv;
    }
}
