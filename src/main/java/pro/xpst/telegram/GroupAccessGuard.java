package pro.xpst.telegram;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class GroupAccessGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(GroupAccessGuard.class);

    @Value("${pro.xpst.telegram.bot.users.allowed:}")
    private Set<Long> allowedUsers;

    @Value("${pro.xpst.telegram.bot.groups.allowed:}")
    private Set<Long> allowedGroups;

    @Value("${pro.xpst.telegram.bot.trigger.phrases:}")
    private List<String> rawTriggers;

    @Value("${pro.xpst.telegram.bot.username}")
    private String botUsername;

    private List<String> triggers;
    private String botMention;

    @PostConstruct
    void init() {
        this.triggers = new ArrayList<>();
        if (this.rawTriggers != null) {
            for (String t : this.rawTriggers) {
                if (t == null) continue;
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) {
                    this.triggers.add(trimmed.toLowerCase());
                }
            }
        }
        this.triggers.sort(Comparator.comparingInt(String::length).reversed());
        String name = this.botUsername == null ? "" : this.botUsername.trim();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        this.botMention = "@" + name;
        LOGGER.debug("GroupAccessGuard initialized: allowedUsers={}, allowedGroups={}, triggers={}, botMention={}",
                allowedUsers, allowedGroups, triggers, botMention);
    }

    public boolean isUserAndChatAllowed(Message aMessage) {
        if (aMessage == null || aMessage.getFrom() == null) {
            return false;
        }
        Chat chat = aMessage.getChat();
        if (chat == null) {
            return false;
        }
        if (chat.isUserChat()) {
            Long userId = aMessage.getFrom().getId();
            return this.allowedUsers == null || this.allowedUsers.isEmpty() || this.allowedUsers.contains(userId);
        }
        if (chat.isGroupChat() || chat.isSuperGroupChat()) {
            Long chatId = aMessage.getChatId();
            return this.allowedGroups == null || this.allowedGroups.isEmpty() || this.allowedGroups.contains(chatId);
        }
        return false;
    }

    public Optional<String> extractPromptFromMessage(Message aMessage) {
        if (aMessage == null || !aMessage.hasText()) {
            return Optional.empty();
        }
        String text = aMessage.getText();
        Chat chat = aMessage.getChat();

        if (chat == null || chat.isUserChat()) {
            return Optional.of(text);
        }

        if (chat.isGroupChat() || chat.isSuperGroupChat()) {
            String lower = text.toLowerCase();
            String mentionLower = this.botMention.toLowerCase();
            int mentionIdx = lower.indexOf(mentionLower);
            if (mentionIdx >= 0) {
                String stripped = cleanStripped(text.substring(0, mentionIdx) + text.substring(mentionIdx + this.botMention.length()));
                return stripped.isEmpty() ? Optional.empty() : Optional.of(stripped);
            }
            for (String trigger : this.triggers) {
                int idx = lower.indexOf(trigger);
                if (idx >= 0) {
                    String stripped = cleanStripped(text.substring(0, idx) + text.substring(idx + trigger.length()));
                    return stripped.isEmpty() ? Optional.empty() : Optional.of(stripped);
                }
            }
            return Optional.empty();
        }

        return Optional.of(text);
    }

    private static String cleanStripped(String s) {
        return s.replaceAll("^[\\s,.:;!?\\-—–]+", "")
                .replaceAll("[\\s,.:;\\-—–]+$", "")
                .trim();
    }
}
