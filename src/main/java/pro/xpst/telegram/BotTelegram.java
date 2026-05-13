package pro.xpst.telegram;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.extensions.bots.commandbot.CommandLongPollingTelegramBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import pro.xpst.ai.AiService;
import pro.xpst.ai.AiServiceFactory;
import pro.xpst.telegram.commands.ModelCommand;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component("botTelegram")
public class BotTelegram extends CommandLongPollingTelegramBot implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(BotTelegram.class);

    @Getter
    private final String botToken;
    private final AiServiceFactory aiServiceFactory;
    private final GroupAccessGuard groupAccessGuard;
    private final MessageFreshnessGuard messageFreshnessGuard;
    private final ModelCommand modelCommand;

    public BotTelegram(@Value("${pro.xpst.telegram.bot.token}") String aBotToken,
                       @Value("${pro.xpst.telegram.bot.username}") String aBotName,
                       @Value("${pro.xpst.telegram.bot.users.admin:}") Set<Long> adminUsers,
                       AiServiceFactory aiServiceFactory,
                       GroupAccessGuard groupAccessGuard,
                       MessageFreshnessGuard messageFreshnessGuard) {
        super(new OkHttpTelegramClient(aBotToken), true, () -> normalizeUsername(aBotName));
        this.botToken = aBotToken;
        this.aiServiceFactory = aiServiceFactory;
        this.groupAccessGuard = groupAccessGuard;
        this.messageFreshnessGuard = messageFreshnessGuard;

        this.modelCommand = new ModelCommand(this, adminUsers);
        this.register(this.modelCommand);
        this.setMenuButton();
    }

    private static String normalizeUsername(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }

    public AiService getAiService(Long aChatId) {
        return this.aiServiceFactory.getInstance(aChatId);
    }

    public AiServiceFactory getAiServiceFactory() {
        return this.aiServiceFactory;
    }

    @Override
    public void processNonCommandUpdate(Update anUpdate) {
        LOGGER.debug("processNonCommandUpdate()");

        if (null == anUpdate) {
            return;
        }

        if (anUpdate.hasCallbackQuery() && ModelCommand.isModelCallbackQuery(anUpdate.getCallbackQuery())) {
            // CallbackQuery.getMessage() returns MaybeInaccessibleMessage. An InaccessibleMessage
            // (older than 48h or deleted) is unambiguously stale; otherwise compare Message.date.
            Message keyboardMsg = anUpdate.getCallbackQuery().getMessage() instanceof Message m ? m : null;
            if (anUpdate.getCallbackQuery().getMessage() != null && keyboardMsg == null) {
                LOGGER.debug("Skipping inaccessible callback query: callbackId={}",
                        anUpdate.getCallbackQuery().getId());
            } else if (!messageFreshnessGuard.isFresh(keyboardMsg)) {
                LOGGER.debug("Skipping stale callback query: callbackId={}, keyboardDate={}, cutoff={}",
                        anUpdate.getCallbackQuery().getId(),
                        keyboardMsg == null ? null : keyboardMsg.getDate(),
                        messageFreshnessGuard.getCutoffEpochSeconds());
            } else {
                modelCommand.processCallbackQuery(anUpdate);
            }
        }

        if (anUpdate.hasMessage()) {
            if (!messageFreshnessGuard.isFresh(anUpdate.getMessage())) {
                LOGGER.debug("Skipping stale message: chatId={}, messageId={}, date={}, cutoff={}",
                        anUpdate.getMessage().getChatId(),
                        anUpdate.getMessage().getMessageId(),
                        anUpdate.getMessage().getDate(),
                        messageFreshnessGuard.getCutoffEpochSeconds());
            } else {
                processMessage(anUpdate);
            }
        }
    }

    private void processMessage(Update anUpdate) {
        LOGGER.debug("processMessage()");

        if (!anUpdate.getMessage().hasText() || null == anUpdate.getMessage().getChat()) {
            return;
        }

        Message message = anUpdate.getMessage();
        LOGGER.debug("Incoming message: ChatId={} (type={}, title={}), UserId={} ({}), MessageId={}",
                message.getChatId(),
                message.getChat().getType(),
                message.getChat().getTitle(),
                message.getFrom().getId(),
                message.getFrom().getUserName(),
                message.getMessageId());

        if (!groupAccessGuard.isUserAndChatAllowed(message)) {
            LOGGER.debug("Access denied for ChatId={}, UserId={}", message.getChatId(), message.getFrom().getId());
            return;
        }

        Optional<String> promptOpt = groupAccessGuard.extractPromptFromMessage(message);
        if (promptOpt.isEmpty()) {
            LOGGER.debug("Group message dropped (no trigger / mention): chatId={}, messageId={}",
                    message.getChatId(), message.getMessageId());
            return;
        }
        String prompt = promptOpt.get();

        LOGGER.debug("Got message: MessageId: {}, UserId: {}, ChatId: {}, prompt: {}",
                message.getMessageId(), message.getFrom().getId(), message.getChatId(), prompt);
        this.sendChatAction(message.getChatId().toString(), ActionType.TYPING.toString());
        try {
            String response = this.getAiService(message.getChatId()).generate(prompt);
            this.sendMessage(message.getChatId(), response);
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to generate reply for ChatId={}: {}", message.getChatId(), ex.getMessage(), ex);
            this.sendMessage(message.getChatId(), "Sorry, something went wrong while processing your message.");
        }
    }

    @Override
    public boolean filter(Message aMessage) {
        if (!groupAccessGuard.isUserAndChatAllowed(aMessage)) {
            return false;
        }
        LOGGER.debug("Got message: {} from User: {} ({})",
                new Object[]{aMessage.getText(), aMessage.getFrom().getUserName(), aMessage.getFrom().getId()});
        return super.filter(aMessage);
    }

    private void setMenuButton() {
        LOGGER.debug("setMenuButton()");
        List<BotCommand> commands = new ArrayList<>();
        this.getRegisteredCommands().forEach(iBotCommand -> commands.add((BotCommand) iBotCommand));
        try {
            this.telegramClient.execute(new SetMyCommands(commands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException ex) {
            LOGGER.error("Error adding menu commands", ex);
        }
    }

    public void sendInlineKeyboardMarkup(Long chatId, String message, InlineKeyboardMarkup inlineKeyboardMarkup) {
        SendMessage sendMessage = new SendMessage(chatId.toString(), message);
        sendMessage.enableMarkdown(true);
        sendMessage.setReplyMarkup(inlineKeyboardMarkup);
        try {
            this.telegramClient.execute(sendMessage);
        } catch (TelegramApiException ex) {
            LOGGER.error("Error while sending an InlineKeyboardMarkup", ex);
        }
    }

    public void sendMessage(Long aChatId, String aMessage) {
        LOGGER.debug("sendMessage(chatId={}, len={}, preview={})",
                aChatId, aMessage.length(),
                aMessage.length() > 200 ? aMessage.substring(0, 200) + "…" : aMessage);
        if (aMessage.length() > 4096) {
            List<String> messageParts = new ArrayList<>();
            int endIndex;
            for (int startIndex = 0; startIndex < aMessage.length(); startIndex = endIndex) {
                endIndex = Math.min(startIndex + 4096, aMessage.length());
                messageParts.add(aMessage.substring(startIndex, endIndex));
            }
            for (String part : messageParts) {
                this.sendMessage(aChatId, part);
            }
        } else {
            SendMessage snd = new SendMessage(aChatId.toString(), aMessage);
            snd.setParseMode(ParseMode.MARKDOWN);
            try {
                this.telegramClient.execute(snd);
            } catch (TelegramApiRequestException ex) {
                if (isMarkdownParseError(ex)) {
                    LOGGER.warn("Markdown parse failed (chatId={}); resending as plain text. Reason: {}",
                            aChatId, ex.getApiResponse());
                    SendMessage plain = new SendMessage(aChatId.toString(), aMessage);
                    try {
                        this.telegramClient.execute(plain);
                    } catch (Exception ex2) {
                        LOGGER.error("Plain-text fallback also failed for chatId={}: {}", aChatId, aMessage, ex2);
                    }
                } else {
                    LOGGER.error("Error while sending a message: {}", aMessage, ex);
                }
            } catch (Exception ex) {
                LOGGER.error("Error while sending a message: {}", aMessage, ex);
            }
        }
    }

    private static boolean isMarkdownParseError(TelegramApiRequestException ex) {
        String resp = ex.getApiResponse();
        return resp != null && resp.contains("can't parse entities");
    }

    public void deleteMessage(Long aChatId, Integer aMessageId) {
        LOGGER.debug("deleteMessage()");
        DeleteMessage deleteMessage = new DeleteMessage(aChatId.toString(), aMessageId);
        try {
            this.telegramClient.execute(deleteMessage);
        } catch (Exception ex) {
            LOGGER.error("Error while deleting a message", ex);
        }
    }

    private void sendChatAction(String aChatId, String aChatAction) {
        LOGGER.debug("sendChatAction()");
        SendChatAction sendChatAction = new SendChatAction(aChatId, aChatAction);
        try {
            this.telegramClient.execute(sendChatAction);
        } catch (TelegramApiException ex) {
            LOGGER.error("Error sending chat action", ex);
        }
    }
}
