package ru.braingm.Excursions;


public record Excursion(int id, String title, String description) {
    public Excursion(int id, String title, String description) {
        if (title == null || title.trim().isEmpty()) throw new IllegalArgumentException("Invalid title");
        this.id = id;
        this.title = title.trim();
        this.description = description.trim();
    }
}