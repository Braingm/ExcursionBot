package ru.braingm.Excursions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.braingm.ConfigLoader;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdminHandler {
    private final ExcursionBot bot;
    private final DatabaseConnector connector;
    private final Logger logger;
    private final Map<Long, AdminState> adminStates = new HashMap<>();
    private final Map<Long, Excursion> adminPendingExcursions = new HashMap<>();
    private final long adminId;
    private String excursionTitle = "";

    //ENUM на состояния админа, возможно потом нужно будет вынести в отдельный класс
    public enum AdminState {
        NONE, AWAITING_ADD_TITLE, AWAITING_ADD_DESCRIPTION, AWAITING_EDIT_DESCRIPTION, AWAITING_EDIT_TITLE
    }

    public AdminHandler(ExcursionBot bot) {
        this.bot = bot;
        this.connector = DatabaseConnector.getInstance();
        this.logger = LoggerFactory.getLogger(AdminHandler.class);
        this.adminId = Long.parseLong(new ConfigLoader().getProperty("admin.chatId"));
    }

    public void handleAdminCallback(CallbackQuery callbackQuery) {
        //Проверка что работает админ
        if (!callbackQuery.getFrom().getId().equals(adminId)) {
            bot.sendMessage(callbackQuery.getFrom().getId(), "Только для администратора");
        } else {
            String[] data = callbackQuery.getData().split("_");

            //Синхронизация с базой
            if (data[1].equals("sync")) {
                bot.updateExcursionList(connector.loadExcursionsFromDatabase());
                bot.sendMessage(adminId, "*Экскурсии синхронизированы*");
            }

            //Добавление новой экскурсии
            if (data[1].equals("addExcursion")) {
                handleAddExcursion();
            }

            if (data[1].equals("callList")) {
                sendAdminExcursionsList(0);
            }
            if (data[1].equals("page")) {
                sendAdminExcursionsList(Integer.parseInt(data[2]));
            }
            if (data[1].equals("delete")) {
                handleDeleteExcursion(Integer.parseInt(data[2]));
            }
            if (data[1].equals("edit")) {
                adminPendingExcursions.put(adminId, connector.getExcursion(Integer.parseInt(data[2])));
                askForNewTitle();
            }
        }
    }

    public void handleAdminMessage(Message message) {
        if (!message.getChatId().equals(adminId)) {
            bot.sendMessage(message.getChatId(), "Только для администратора");
        } else {
            String text = message.getText();
            if (text.equals("/adminpanel")) {
                sendAdminPanel();
            }
        }
    }

    public void sendAdminPanel() {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        //Кнопка синхронизации
        InlineKeyboardButton syncButton = new InlineKeyboardButton("Синхронизировать с базой");
        syncButton.setCallbackData("admin_sync");
        rows.add(List.of(syncButton));

        //Кнопка добавления
        InlineKeyboardButton addButton = new InlineKeyboardButton("Добавить экскурсию");
        addButton.setCallbackData("admin_addExcursion");
        rows.add(List.of(addButton));

        //Кнопка редактирования
        InlineKeyboardButton editListButton = new InlineKeyboardButton("Изменить или удалить");
        editListButton.setCallbackData("admin_callList");
        rows.add(List.of(editListButton));

        keyboard.setKeyboard(rows);

        //Отправка клавиатуры
        SendMessage message = new SendMessage();
        message.setChatId(adminId);
        message.setText("Выберете действие: ");
        message.setReplyMarkup(keyboard);
        bot.sendMessage(message);
    }

    private void handleAddExcursion() {
        adminStates.put(adminId, AdminState.AWAITING_ADD_TITLE);
        bot.sendMessage(adminId, "Введите название экскурсии");
    }

    private void handleAddTitle(String title) {
        this.excursionTitle = title;
        adminStates.put(adminId, AdminState.AWAITING_ADD_DESCRIPTION);
        bot.sendMessage(adminId, "Теперь введите описание экскурсии");
    }

    private void handleAddDescription(String description) {
        connector.writeExcursion(excursionTitle, description);
        adminStates.remove(adminId);
        bot.updateExcursionList(connector.loadExcursionsFromDatabase());
        bot.sendMessage(adminId, "Успешно добавлено");
    }


    public void handleAdminState(String text) {
        AdminState state = adminStates.getOrDefault(adminId, AdminState.NONE);
        try {
            switch (state) {
                case AWAITING_ADD_TITLE -> handleAddTitle(text); // Добавлено
                case AWAITING_ADD_DESCRIPTION -> handleAddDescription(text); // Добавлено
                case AWAITING_EDIT_TITLE -> handleEditTitle(text);
                case AWAITING_EDIT_DESCRIPTION -> handleEditDescription(text);
            }
        } catch (Exception e) {
            logger.error("Ошибка обработки состояния", e);
            bot.sendMessage(adminId, "Ошибка: " + e.getMessage());
        }
    }

    //Вроде должно нормально работать
    private void handleDeleteExcursion(int excursionId) {
        try (PreparedStatement pstmt = connector.getConnection().prepareStatement(
                "DELETE FROM excursions WHERE id = ?")) {
            pstmt.setInt(1, excursionId);
            pstmt.executeUpdate();
            bot.updateExcursionList(connector.loadExcursionsFromDatabase());
            bot.sendMessage(adminId, "Экскурсия удалена!");
        } catch (SQLException e) {
            logger.error("Failed to delete excursion", e);
            bot.sendMessage(adminId, "Ошибка: " + e.getMessage());
        }
    }

    //Надеюсь работает
    private void sendAdminExcursionsList(int page) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Отображаем экскурсии текущей страницы
        int start = page * ExcursionBot.EXCURSIONS_PER_PAGE;
        int end = Math.min(start + ExcursionBot.EXCURSIONS_PER_PAGE, bot.getExcursions().size());
        for (int i = start; i < end; i++) {
            List<InlineKeyboardButton> excursionRow = getInlineKeyboardButtons(i);
            rows.add(excursionRow);
        }

        // Пагинация
        List<InlineKeyboardButton> paginationRow = getInlineKeyboardButtons(page, end);
        if (!paginationRow.isEmpty()) rows.add(paginationRow);

        keyboardMarkup.setKeyboard(rows);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(adminId));
        message.setText("Список экскурсий для управления:");
        message.setReplyMarkup(keyboardMarkup);

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            logger.error("Ошибка отправки списка экскурсий", e);
        }
    }

    private List<InlineKeyboardButton> getInlineKeyboardButtons(int page, int end) {
        List<InlineKeyboardButton> paginationRow = new ArrayList<>();
        if (page > 0) {
            InlineKeyboardButton prevButton = new InlineKeyboardButton("◀️ Назад");
            prevButton.setCallbackData("admin" + "_page_" + (page - 1));
            paginationRow.add(prevButton);
        }
        if (end < bot.getExcursions().size()) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton("▶️ Вперед");
            nextButton.setCallbackData("admin" + "_page_" + (page + 1));
            paginationRow.add(nextButton);
        }
        return paginationRow;
    }

    private List<InlineKeyboardButton> getInlineKeyboardButtons(int i) {
        Excursion excursion = bot.getExcursions().get(i);
        List<InlineKeyboardButton> excursionRow = new ArrayList<>();

        // Кнопка с названием экскурсии
        InlineKeyboardButton titleButton = new InlineKeyboardButton(excursion.title());
        titleButton.setCallbackData("view_" + excursion.id());
        excursionRow.add(titleButton);

        // Кнопка "Удалить"
        InlineKeyboardButton deleteButton = new InlineKeyboardButton("❌ Удалить");
        deleteButton.setCallbackData("admin" + "_delete_" + excursion.id());
        excursionRow.add(deleteButton);

        // Кнопка "Редактировать"
        InlineKeyboardButton editButton = new InlineKeyboardButton("✏️ Редактировать");
        editButton.setCallbackData("admin" + "_edit_" + excursion.id());
        excursionRow.add(editButton);
        return excursionRow;
    }

    private void askForNewTitle() {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        var keepButton = new InlineKeyboardButton("Оставить текущее название");
        keepButton.setCallbackData("keep_title");
        rows.add(List.of(keepButton));
        keyboard.setKeyboard(rows);
        adminStates.put(adminId, AdminState.AWAITING_EDIT_TITLE);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(adminId));
        message.setText("Введите новое название или нажмите кнопку:");
        message.setReplyMarkup(keyboard);

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            logger.error("Ошибка отправки клавиатуры", e);
        }
    }

    private void handleEditTitle(String text) {
        if (!text.equals("keep_title")) {
            Excursion excursion = adminPendingExcursions.get(adminId);
            excursion = new Excursion(excursion.id(), text, excursion.description());
            adminPendingExcursions.put(adminId, excursion);
        }
        bot.sendMessage(adminId, "Введите новое описание:");
        adminStates.put(adminId, AdminState.AWAITING_EDIT_DESCRIPTION);
    }

    private void handleEditDescription(String text) {
        Excursion excursion = adminPendingExcursions.get(adminId);
        try (PreparedStatement stmt = connector.getConnection().prepareStatement("UPDATE excursions SET title = ?, description = ? WHERE id = ?")) {

            stmt.setString(1, excursion.title());
            stmt.setString(2, text);
            stmt.setInt(3, excursion.id());
            stmt.executeUpdate();
            bot.updateExcursionList(connector.loadExcursionsFromDatabase());
            bot.sendMessage(adminId, "Успешно изменено");
            adminStates.remove(adminId);
            logger.info("Excursion changed");
        } catch (SQLException ex) {
            logger.error("Error changing excursion");
            throw new RuntimeException(ex);
        }
    }
}
