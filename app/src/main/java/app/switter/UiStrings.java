package app.switter;

import android.content.Context;

/** Presentation-only localization of legacy internal exception messages. */
final class UiStrings {
    private UiStrings() {}
    private static String suffix(Context context,String tail){return tail.isEmpty()?"":" "+error(context,tail.trim());}
    static String error(Context context,String raw) {
        if(raw.startsWith("Введите полный http:// или https:// URL без логина и пароля"))return context.getString(R.string.invalid_url)+suffix(context,raw.substring(59));
        if(raw.startsWith("Вход не выполнен: проверьте адрес скрипта, логин и пароль"))return context.getString(R.string.login_failed)+suffix(context,raw.substring(57));
        if(raw.startsWith("Не удалось прочитать записи. Доступен веб-режим. "))return context.getString(R.string.posts_failed)+suffix(context,raw.substring(49));
        if(raw.startsWith("Укажите путь к административному скрипту"))return context.getString(R.string.admin_path_required)+suffix(context,raw.substring(40));
        if(raw.startsWith("Укажите хост SOCKS5 и порт от 1 до 65535"))return context.getString(R.string.invalid_proxy)+suffix(context,raw.substring(40));
        if(raw.startsWith("Некорректный адрес администрирования"))return context.getString(R.string.invalid_admin_url)+suffix(context,raw.substring(36));
        if(raw.startsWith("Разрешены JPG, PNG, GIF и WEBP"))return context.getString(R.string.image_formats)+suffix(context,raw.substring(30));
        if(raw.startsWith("PIN должен содержать 4–12 цифр"))return context.getString(R.string.pin_digits)+suffix(context,raw.substring(30));
        if(raw.startsWith("Не удалось сохранить настройки"))return context.getString(R.string.save_failed)+suffix(context,raw.substring(30));
        if(raw.startsWith("Слишком много перенаправлений"))return context.getString(R.string.redirect_limit)+suffix(context,raw.substring(29));
        if(raw.startsWith("Сервер не подтвердил операцию"))return context.getString(R.string.server_unconfirmed)+suffix(context,raw.substring(29));
        if(raw.startsWith("Неизвестный путь изображения"))return context.getString(R.string.image_path)+suffix(context,raw.substring(28));
        if(raw.startsWith("Недопустимое перенаправление"))return context.getString(R.string.invalid_redirect)+suffix(context,raw.substring(28));
        if(raw.startsWith("Заполните название и текст"))return context.getString(R.string.post_required)+suffix(context,raw.substring(26));
        if(raw.startsWith("Неизвестный формат даты: "))return context.getString(R.string.date_format)+suffix(context,raw.substring(25));
        if(raw.startsWith("Неизвестный формат записи"))return context.getString(R.string.post_format)+suffix(context,raw.substring(25));
        if(raw.startsWith("Изображение больше 5 МБ"))return context.getString(R.string.image_too_large)+suffix(context,raw.substring(23));
        if(raw.startsWith("Превышен размер файла: "))return context.getString(R.string.file_limit)+suffix(context,raw.substring(23));
        if(raw.startsWith("Страница не распознана"))return context.getString(R.string.unrecognized_page)+suffix(context,raw.substring(22));
        if(raw.startsWith("Пустой ответ сервера"))return context.getString(R.string.empty_response)+suffix(context,raw.substring(20));
        if(raw.startsWith("Ошибка входа"))return context.getString(R.string.login_error)+suffix(context,raw.substring(12));
        if(raw.startsWith("Повтор ID"))return context.getString(R.string.duplicate_id)+suffix(context,raw.substring(9));
        if(raw.startsWith(" МБ"))return context.getString(R.string.mb)+suffix(context,raw.substring(3));
        return raw;
    }
}
