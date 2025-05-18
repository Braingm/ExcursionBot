package ru.braingm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.braingm.Excursions.ExcursionBot;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new ExcursionBot());
            logger.info("Bot started!");
            Thread.currentThread().join();
        } catch (InterruptedException | TelegramApiException e) {
            logger.error("Bot error: ", e);
        }
    }
}