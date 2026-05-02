package pro.xpst.telegram.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.extensions.bots.commandbot.commands.IBotCommand;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import pro.xpst.telegram.BotTelegram;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ModelCommand extends BotCommand implements IBotCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCommand.class);
    private static final String COMMAND = "model";
    private static final String DELIMITER = ":";

    private final BotTelegram bot;
    private final Set<Long> adminUsers;

    public ModelCommand(BotTelegram bot, Set<Long> adminUsers) {
        super(COMMAND, "Prints the current model and allows to set a new one (admins only)");
        this.bot = bot;
        this.adminUsers = adminUsers;
    }

    @Override
    public String getCommandIdentifier() {
        return getCommand();
    }

    @Override
    public void processMessage(TelegramClient aTelegramClient, Message aMessage, String[] anArguments) {
        LOGGER.debug("processMessage()");

        if (!isAdmin(aMessage.getFrom().getId())) {
            LOGGER.debug("Non-admin user {} tried /model — dropping", aMessage.getFrom().getId());
            return;
        }

        if (0 == anArguments.length) {
            bot.sendMessage(aMessage.getChatId(),
                    "The current model is: " + bot.getAiService(aMessage.getChatId()).getModel());
            bot.sendInlineKeyboardMarkup(aMessage.getChatId(), "Please specify a model", createButtons());
        } else if (anArguments.length > 1) {
            bot.sendMessage(aMessage.getChatId(), aMessage.getFrom().getUserName() + ", please specify a model");
        } else {
            changeModel(aMessage.getChatId(), anArguments[0]);
        }
    }

    public static boolean isModelCallbackQuery(CallbackQuery aCallbackquery) {
        LOGGER.debug("isModelCallbackQuery()");
        return null != aCallbackquery.getData() && aCallbackquery.getData().startsWith(COMMAND);
    }

    private boolean isAdmin(Long userId) {
        return this.adminUsers != null && !this.adminUsers.isEmpty() && this.adminUsers.contains(userId);
    }

    private void changeModel(Long aChatId, String aModel) {
        LOGGER.debug("changeModel()");
        bot.getAiServiceFactory().setModel(aChatId, aModel);
        bot.sendMessage(aChatId, "Done, current model is: " + bot.getAiService(aChatId).getModel());
    }

    private InlineKeyboardMarkup createButtons() {
        LOGGER.debug("createButtons()");
        List<InlineKeyboardButton> buttons = bot.getAiServiceFactory().getAllModels()
                .stream()
                .map(s -> InlineKeyboardButton.builder()
                        .text(s)
                        .callbackData(COMMAND + DELIMITER + s)
                        .build())
                .collect(Collectors.toList());
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(buttons))
                .build();
    }

    public void processCallbackQuery(Update anUpdate) {
        LOGGER.debug("processCallbackQuery()");
        CallbackQuery callbackquery = anUpdate.getCallbackQuery();
        if (callbackquery == null || callbackquery.getFrom() == null) {
            return;
        }
        if (!isAdmin(callbackquery.getFrom().getId())) {
            LOGGER.debug("Non-admin user {} clicked /model button — dropping", callbackquery.getFrom().getId());
            return;
        }
        if (null != callbackquery.getData() && callbackquery.getData().startsWith(COMMAND)) {
            String[] data = callbackquery.getData().split(DELIMITER);
            changeModel(callbackquery.getMessage().getChatId(), data[1]);
            bot.deleteMessage(callbackquery.getMessage().getChatId(), callbackquery.getMessage().getMessageId());
        }
    }
}
