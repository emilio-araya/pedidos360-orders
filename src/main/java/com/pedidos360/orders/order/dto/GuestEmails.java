package com.pedidos360.orders.order.dto;

import java.util.Locale;

/** Email rules shared by the guest endpoints. */
public final class GuestEmails {

    private GuestEmails() {
    }

    /** Trims and lowercases; null stays null so validation reports it. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** First character of the local part, then {@code ***@domain}: {@code l***@duocuc.cl}. */
    public static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
