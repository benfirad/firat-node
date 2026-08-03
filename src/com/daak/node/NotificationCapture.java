package com.daak.node;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class NotificationCapture {
    static final class Item {
        final String sender;
        final String text;

        Item(String sender, String text) {
            this.sender = clean(sender);
            this.text = clean(text);
        }
    }

    private NotificationCapture() { }

    static List<Item> extract(Notification notification) {
        LinkedHashMap<String, Item> unique = new LinkedHashMap<String, Item>();
        if (notification == null || notification.extras == null) return new ArrayList<Item>();
        Bundle extras = notification.extras;
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));

        try {
            Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (bundles != null) {
                for (Parcelable parcelable : bundles) {
                    if (!(parcelable instanceof Bundle)) continue;
                    Bundle message = (Bundle)parcelable;
                    String sender = "";
                    Parcelable senderValue = message.getParcelable("sender_person");
                    if (senderValue instanceof Person) sender = text(((Person)senderValue).getName());
                    if (sender.length() == 0) sender = text(message.getCharSequence("sender"));
                    add(unique, sender.length() == 0 ? title : sender, text(message.getCharSequence("text")));
                }
            }
        } catch (RuntimeException ignored) { }

        if (!unique.isEmpty()) return new ArrayList<Item>(unique.values());

        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) add(unique, title, text(line));
        }

        if (!unique.isEmpty()) return new ArrayList<Item>(unique.values());

        String body = first(
                text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)));
        add(unique, title, body);
        return new ArrayList<Item>(unique.values());
    }

    static String conversationSender(String title, String messageSender) {
        title = clean(title); messageSender = clean(messageSender);
        title = title.replaceFirst("(?i)\\s*\\(\\d+\\s+(messages|mensajes|mesaj|mesajlar)\\)\\s*$", "");
        if (title.length() == 0) return messageSender.length() == 0 ? "WhatsApp" : messageSender;
        if (messageSender.length() == 0 || title.equalsIgnoreCase(messageSender)) return title;
        return title + " • " + messageSender;
    }

    private static void add(LinkedHashMap<String, Item> unique, String sender, String body) {
        sender = clean(sender); body = clean(body);
        if (body.length() == 0) return;
        if (sender.length() == 0) sender = "Unknown";
        String key = sender + "\n" + body;
        if (!unique.containsKey(key)) unique.put(key, new Item(sender, body));
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && value.length() > 0) return value;
        return "";
    }

    private static String text(CharSequence value) { return value == null ? "" : clean(value.toString()); }

    static String clean(String value) {
        if (value == null) return "";
        return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }
}
