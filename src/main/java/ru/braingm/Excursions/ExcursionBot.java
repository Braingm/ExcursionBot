package ru.braingm.Excursions;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.braingm.ConfigLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ExcursionBot extends TelegramLongPollingBot {
    private static final Logger logger = LoggerFactory.getLogger(ExcursionBot.class);
    @Getter
    private List<Excursion> excursions;
    private final DatabaseConnector connector;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final AdminHandler adminHandler;
    private final long adminChatId;
    public static final int EXCURSIONS_PER_PAGE = 8;

    public ExcursionBot() {
        //Config
        ConfigLoader config = new ConfigLoader();
        this.adminChatId = Long.parseLong(config.getProperty("admin.chatId"));
        this.adminHandler = new AdminHandler(this);

        //Database
        this.connector = DatabaseConnector.getInstance();
        this.excursions = connector.loadExcursionsFromDatabase();
        startCacheRefresh();
    }


    private void startCacheRefresh() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                excursions = connector.loadExcursionsFromDatabase();
                logger.info("Cache refreshed");
            } catch (Exception e) {
                logger.error("Cache refresh error", e);
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasCallbackQuery()) {
            long chatId = update.getCallbackQuery().getFrom().getId();
            String data = update.getCallbackQuery().getData();
            if (data.contains("admin")) {
                adminHandler.handleAdminCallback(update.getCallbackQuery());
            }
            if (data.contains("view_")) {
                int excursionId = Integer.parseInt(data.substring(5));
                sendExcursion(chatId, excursionId);
                try {
                    execute(AnswerCallbackQuery.builder().callbackQueryId(update.getCallbackQuery().getId()).build());
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
            }
            if (data.contains("send")) {
                String[] context = data.split("_");
                switch (context[1]) {
                    case "excursionLIst" -> sendExcursionsList(chatId, 0);
                    case "schedule" -> sendSchedule(chatId);
                    case "info" -> sendInfo(chatId);
                    case "contacts" -> sendContacts(chatId);
                }
            }
            if (data.contains("deleteMessage")) {
                Message message = (Message) update.getCallbackQuery().getMessage();
                deleteMessage(chatId, message.getMessageId());
            }
            if (data.contains("page")) {
                String[] context = data.split("_");
                Message message = (Message) update.getCallbackQuery().getMessage();
                deleteMessage(chatId, message.getMessageId());
                sendExcursionsList(chatId, Integer.parseInt(context[1]));
            }
        } else if (update.hasMessage() && update.getMessage().hasText()) {
            if (update.getMessage().getText().contains("/admin")) {
                adminHandler.handleAdminMessage(update.getMessage());
            }
            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();

            if (chatId == adminChatId) {
                adminHandler.handleAdminState(text);
            }
            if (text.equals("/start")) {
                sendMainMenu(chatId);
            }
        }
    }

    private void deleteMessage(long chatId, int messageId) {
        DeleteMessage deleteMessage = new DeleteMessage();
        deleteMessage.setChatId(chatId);
        deleteMessage.setMessageId(messageId);
        try {
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendExcursion(long chatId, int excursionId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton deleteButton = new InlineKeyboardButton("Назад к списку");
        deleteButton.setCallbackData("deleteMessage");
        rows.add(List.of(deleteButton));
        keyboard.setKeyboard(rows);

        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(excursions.stream()
                .filter(excursion -> excursionId == excursion.id())
                .findFirst()
                .get().description());
        message.enableMarkdown(true);
        message.setReplyMarkup(keyboard);
        sendMessage(message);
    }


    void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.enableMarkdown(true);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Message send failed", e);
        }
    }

    void sendMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Message send failed", e);
        }
    }

    void sendExcursionsList(long chatId, int page) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        int start = page * EXCURSIONS_PER_PAGE;
        int end = Math.min(start + EXCURSIONS_PER_PAGE, excursions.size());

        for (int i = start; i < end; i++) {
            Excursion excursion = excursions.get(i);
            InlineKeyboardButton button = new InlineKeyboardButton(excursion.title());
            button.setCallbackData("view_" + excursion.id());
            rows.add(List.of(button));
        }

        // Пагинация
        List<InlineKeyboardButton> navButtons = new ArrayList<>();
        if (page > 0) {
            InlineKeyboardButton prev = new InlineKeyboardButton("◀️ Назад");
            prev.setCallbackData("page_" + (page - 1));
            navButtons.add(prev);
        }
        if (end < excursions.size()) {
            InlineKeyboardButton next = new InlineKeyboardButton("▶️ Вперед");
            next.setCallbackData("page_" + (page + 1));
            navButtons.add(next);
        }
        if (!navButtons.isEmpty()) rows.add(navButtons);

        keyboard.setKeyboard(rows);
        SendMessage message = new SendMessage(String.valueOf(chatId), "Список экскурсий:");
        message.setReplyMarkup(keyboard);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            logger.error("Failed to send excursions list", e);
        }
    }

    private void sendSchedule(long chatId) {
        sendMessage(chatId, connector.loadData(1));
    }

    private void sendInfo(long chatId) {
        sendMessage(chatId, connector.loadData(2));
    }

    private void sendContacts(long chatId) {
        sendMessage(chatId, connector.loadData(3));
    }

    @Override
    public String getBotUsername() {
        return new ConfigLoader().getProperty("bot.name");
    }

    @Override
    public String getBotToken() {
        return new ConfigLoader().getProperty("bot.token");
    }

    public void updateExcursionList(List<Excursion> list) {
        this.excursions = list;
    }

    public void sendMainMenu(long chatId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        //Кнопка экскурсий
        InlineKeyboardButton excursionsButton = new InlineKeyboardButton("Автобусные экскурсии");
        excursionsButton.setCallbackData("send_excursionLIst");
        rows.add(List.of(excursionsButton));

        //Кнопка расписание
        InlineKeyboardButton scheduleButton = new InlineKeyboardButton("Расписание");
        scheduleButton.setCallbackData("send_schedule");
        rows.add(List.of(scheduleButton));

        //Кнопка информация
        InlineKeyboardButton infoButton = new InlineKeyboardButton("Информация");
        infoButton.setCallbackData("send_info");
        rows.add(List.of(infoButton));

        //Кнопка контакты
        InlineKeyboardButton contactsButton = new InlineKeyboardButton("Контакты");
        contactsButton.setCallbackData("send_contacts");
        rows.add(List.of(contactsButton));

        keyboard.setKeyboard(rows);

        SendMessage message = new SendMessage();
        message.setText("Выберете действие:");
        message.setChatId(chatId);
        message.setReplyMarkup(keyboard);
        sendMessage(message);

    }
}