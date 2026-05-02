package pro.xpst.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import pro.xpst.ai.impl.ClaudeCliServiceImpl;
import pro.xpst.ai.impl.GeminiCliServiceImpl;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class AiServiceFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiServiceFactory.class);
    private static final String PROVIDER_GEMINI = "gemini";

    private final Set<String> claudeModels;
    private final Set<String> geminiModels;
    private final String defaultClaudeModel;
    private final String defaultGeminiModel;
    private final String defaultProvider;
    private final int expirationMinutes;
    private final String claudeBinary;
    private final String geminiBinary;
    private final long cliTimeoutSeconds;
    private final String claudeWorkdir;
    private final String geminiWorkdir;
    private final String claudeEffort;
    private final String claudeTools;
    private final String geminiTools;
    private final ApplicationContext applicationContext;

    private final Map<Long, AiService> serviceMap = new HashMap<>();

    public AiServiceFactory(
            @Value("${pro.xpst.claude.models}") Set<String> claudeModels,
            @Value("${pro.xpst.gemini.models}") Set<String> geminiModels,
            @Value("${pro.xpst.claude.default-model}") String defaultClaudeModel,
            @Value("${pro.xpst.gemini.default-model}") String defaultGeminiModel,
            @Value("${pro.xpst.default.provider:claude}") String defaultProvider,
            @Value("${pro.xpst.conversation.expiration:60}") int expirationMinutes,
            @Value("${pro.xpst.cli.claude.binary:claude}") String claudeBinary,
            @Value("${pro.xpst.cli.gemini.binary:gemini}") String geminiBinary,
            @Value("${pro.xpst.cli.timeout-seconds:120}") long cliTimeoutSeconds,
            @Value("${pro.xpst.cli.claude.workdir:./claude-cli}") String claudeWorkdir,
            @Value("${pro.xpst.cli.gemini.workdir:./gemini-cli}") String geminiWorkdir,
            @Value("${pro.xpst.cli.claude.effort:}") String claudeEffort,
            @Value("${pro.xpst.cli.claude.tools:}") String claudeTools,
            @Value("${pro.xpst.cli.gemini.tools:}") String geminiTools,
            ApplicationContext applicationContext) {
        this.claudeModels = claudeModels;
        this.geminiModels = geminiModels;
        this.defaultClaudeModel = defaultClaudeModel;
        this.defaultGeminiModel = defaultGeminiModel;
        this.defaultProvider = defaultProvider == null ? "claude" : defaultProvider.trim().toLowerCase();
        this.expirationMinutes = expirationMinutes;
        this.claudeBinary = claudeBinary;
        this.geminiBinary = geminiBinary;
        this.cliTimeoutSeconds = cliTimeoutSeconds;
        this.claudeWorkdir = claudeWorkdir;
        this.geminiWorkdir = geminiWorkdir;
        this.claudeEffort = claudeEffort;
        this.claudeTools = claudeTools;
        this.geminiTools = geminiTools;
        this.applicationContext = applicationContext;
    }

    public AiService getInstance(Long aChatId) {
        return this.serviceMap.computeIfAbsent(aChatId,
                id -> PROVIDER_GEMINI.equals(this.defaultProvider) ? createGemini() : createClaude());
    }

    public Set<String> getAllModels() {
        Set<String> all = new LinkedHashSet<>(this.claudeModels);
        all.addAll(this.geminiModels);
        return all;
    }

    public void setModel(Long aChatId, String aModelName) {
        LOGGER.debug("setModel(chatId={}, model={})", aChatId, aModelName);
        boolean isGemini = this.geminiModels.contains(aModelName);
        boolean isClaude = this.claudeModels.contains(aModelName);
        if (!isGemini && !isClaude) {
            LOGGER.warn("Unknown model: {} — ignoring", aModelName);
            return;
        }
        AiService current = this.serviceMap.get(aChatId);

        if (current == null) {
            current = isGemini ? createGemini() : createClaude();
            this.serviceMap.put(aChatId, current);
        } else if (isGemini && !(current instanceof GeminiCliServiceImpl)) {
            LOGGER.debug("Switching chat {} to Gemini; memory reset", aChatId);
            current = createGemini();
            this.serviceMap.put(aChatId, current);
        } else if (isClaude && !(current instanceof ClaudeCliServiceImpl)) {
            LOGGER.debug("Switching chat {} to Claude; memory reset", aChatId);
            current = createClaude();
            this.serviceMap.put(aChatId, current);
        }
        current.setModel(aModelName);
    }

    private AiService createClaude() {
        return this.applicationContext.getBean(
                ClaudeCliServiceImpl.class,
                this.claudeBinary,
                this.claudeWorkdir,
                this.defaultClaudeModel,
                this.expirationMinutes,
                this.cliTimeoutSeconds,
                this.claudeEffort,
                this.claudeTools);
    }

    private AiService createGemini() {
        return this.applicationContext.getBean(
                GeminiCliServiceImpl.class,
                this.geminiBinary,
                this.geminiWorkdir,
                this.defaultGeminiModel,
                this.expirationMinutes,
                this.cliTimeoutSeconds,
                this.geminiTools);
    }
}
