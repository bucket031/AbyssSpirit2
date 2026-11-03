package com.example.abyssspirit;

import java.util.Locale;

/** The two bodies of the Abyss. */
public enum Form {

    SMALL("small", "small orb"),
    BIG("big", "great spiral");

    private final String id;
    private final String description;

    Form(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() { return id; }
    public String description() { return description; }

    /** Returns null for anything that isn't a form name. */
    public static Form fromInput(String input) {
        if (input == null) return null;
        String wanted = input.toLowerCase(Locale.ROOT);
        for (Form form : values()) {
            if (form.id.equals(wanted)) return form;
        }
        return null;
    }
}
