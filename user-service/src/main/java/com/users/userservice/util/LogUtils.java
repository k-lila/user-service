package com.users.userservice.util;

public final class LogUtils {

    private LogUtils() {
    }

    /**
     * Mascara um e-mail para logging (LGPD): mantém apenas a primeira letra do
     * local-part e o domínio. Ex.: "fulano@email.com" -> "f***@email.com".
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String shown = local.substring(0, 1);
        return shown + "***" + domain;
    }
}
