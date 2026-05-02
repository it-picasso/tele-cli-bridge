package pro.xpst.ai.impl;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Scope("prototype")
public class GeminiCliServiceImpl extends AbstractCliService {

    public GeminiCliServiceImpl(String binary,
                                String workdirPath,
                                String defaultModel,
                                int expirationMinutes,
                                long timeoutSeconds) {
        super(binary, workdirPath, defaultModel, expirationMinutes, timeoutSeconds);
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
        argv.add("-p");
        argv.add(serializedPrompt);
        return argv;
    }
}
