#!/usr/bin/env python3
"""Generates res/values/strings.xml (en) and res/values-ru/strings.xml and checks every R.string/R.plurals reference.

Run from the repository root:  python3 tools/gen_strings.py
"""
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main"

S = {
    "app_name": ("File Manager X", "File Manager X"),
    "home_tagline": ("Every file. Every folder. Root-ready.", "Каждый файл. Каждая папка. С поддержкой root."),
    "home_storage": ("Storage", "Хранилище"),
    "home_quick_access": ("Quick access", "Быстрый доступ"),
    "home_system": ("System", "Система"),
    "storage_used_of": ("%1$s of %2$s used", "Занято %1$s из %2$s"),
    "storage_free": ("%1$s free", "Свободно %1$s"),
    "quick_downloads": ("Downloads", "Загрузки"),
    "quick_pictures": ("Pictures", "Картинки"),
    "quick_camera": ("Camera", "Камера"),
    "quick_music": ("Music", "Музыка"),
    "quick_movies": ("Movies", "Фильмы"),
    "quick_documents": ("Documents", "Документы"),
    "system_root": ("Root directory", "Корень системы"),
    "system_system": ("System partition", "Системный раздел"),
    "system_data": ("Data partition", "Раздел данных"),
    "system_etc": ("Configuration", "Конфигурация"),
    "root_mode": ("Root mode", "Режим root"),
    "root_title": ("Root", "Корень"),
    "root_status_off": ("Off. Turn on to read and write system files.", "Выключен. Включите, чтобы читать и изменять системные файлы."),
    "root_status_checking": ("Waiting for the superuser prompt…", "Ждём ответ менеджера root…"),
    "root_status_active": ("Active. File operations run as root.", "Активен. Файловые операции выполняются от root."),
    "root_status_unavailable": ("Root is not available on this device.", "Root недоступен на этом устройстве."),
    "root_unavailable": ("Root access was not granted.", "Root-доступ не получен."),
    "setting_root_hint": ("Read and write system files with superuser rights", "Чтение и запись системных файлов с правами суперпользователя"),
    "action_back": ("Back", "Назад"),
    "action_cancel": ("Cancel", "Отмена"),
    "action_ok": ("OK", "ОК"),
    "action_apply": ("Apply", "Применить"),
    "action_create": ("Create", "Создать"),
    "action_rename": ("Rename", "Переименовать"),
    "action_delete": ("Delete", "Удалить"),
    "action_copy": ("Copy", "Копировать"),
    "action_cut": ("Move", "Переместить"),
    "action_paste": ("Paste", "Вставить"),
    "action_share": ("Share", "Поделиться"),
    "action_compress": ("Compress", "Сжать"),
    "action_extract": ("Extract", "Распаковать"),
    "action_extract_all": ("Extract all", "Распаковать всё"),
    "action_extract_selected": ("Extract selected", "Распаковать выбранное"),
    "action_select_all": ("Select all", "Выбрать всё"),
    "action_select_this_folder": ("Use this folder", "Выбрать эту папку"),
    "action_search": ("Search", "Поиск"),
    "action_sort": ("Sort", "Сортировка"),
    "action_view_mode": ("List or grid", "Список или сетка"),
    "action_more": ("More", "Ещё"),
    "action_refresh": ("Refresh", "Обновить"),
    "action_retry": ("Try again", "Повторить"),
    "action_show_hidden": ("Show hidden files", "Показать скрытые файлы"),
    "action_hide_hidden": ("Hide hidden files", "Скрыть скрытые файлы"),
    "action_properties": ("Properties", "Свойства"),
    "action_info": ("Archive info", "О файле архива"),
    "action_test": ("Test archive", "Проверить архив"),
    "action_save": ("Save", "Сохранить"),
    "action_discard": ("Discard", "Отбросить"),
    "action_replace": ("Replace", "Заменить"),
    "action_keep_both": ("Keep both", "Оставить оба"),
    "action_skip": ("Skip", "Пропустить"),
    "action_show_password": ("Show password", "Показать пароль"),
    "action_remount_rw": ("Remount read/write", "Перемонтировать для записи"),
    "action_remount_ro": ("Remount read-only", "Перемонтировать только для чтения"),
    "action_donate": ("Support the project", "Поддержать проект"),
    "yes": ("Yes", "Да"),
    "no": ("No", "Нет"),
    "dialog_new_folder": ("New folder", "Новая папка"),
    "dialog_new_file": ("New file", "Новый файл"),
    "dialog_rename": ("Rename", "Переименовать"),
    "dialog_delete_title": ("Delete?", "Удалить?"),
    "dialog_conflict_title": ("Items already exist", "Такие элементы уже есть"),
    "dialog_compress_title": ("Create archive", "Создать архив"),
    "dialog_extract_title": ("Extract to", "Куда распаковать"),
    "dialog_password_title": ("Archive password", "Пароль архива"),
    "dialog_delete_entries_title": ("Remove from archive?", "Удалить из архива?"),
    "dialog_discard_title": ("Discard changes?", "Отбросить изменения?"),
    "dialog_discard_message": ("The file has unsaved changes.", "В файле есть несохранённые изменения."),
    "label_name": ("Name", "Имя"),
    "label_format": ("Format", "Формат"),
    "label_compression": ("Compression", "Сжатие"),
    "label_password": ("Password", "Пароль"),
    "label_password_optional": ("Password (optional)", "Пароль (необязательно)"),
    "label_if_exists": ("If a file already exists", "Если файл уже существует"),
    "hint_zip_aes": ("ZIP archives are protected with AES-256.", "ZIP-архивы защищаются шифрованием AES-256."),
    "level_store": ("Store", "Без сжатия"),
    "level_fast": ("Fast", "Быстро"),
    "level_normal": ("Normal", "Обычное"),
    "level_maximum": ("Maximum", "Максимальное"),
    "extract_to_named": ("New folder next to the archive", "Новая папка рядом с архивом"),
    "extract_here": ("Same folder as the archive", "В папку с архивом"),
    "extract_choose": ("Choose a folder…", "Выбрать папку…"),
    "sort_title": ("Sort by", "Сортировать по"),
    "sort_name": ("Name", "Имени"),
    "sort_size": ("Size", "Размеру"),
    "sort_modified": ("Date", "Дате"),
    "sort_type": ("Type", "Типу"),
    "sort_ascending": ("Ascending", "По возрастанию"),
    "sort_descending": ("Descending", "По убыванию"),
    "sort_folders_first": ("Folders first", "Сначала папки"),
    "folder_empty": ("This folder is empty", "Папка пуста"),
    "archive_folder_empty": ("Nothing in here", "Здесь ничего нет"),
    "search_hint": ("Search in %1$s", "Поиск в «%1$s»"),
    "search_empty": ("Nothing found", "Ничего не найдено"),
    "problem_denied": ("Access denied", "Нет доступа"),
    "problem_denied_hint": ("Turn on root mode on the home screen to open protected folders.", "Включите режим root на главном экране, чтобы открывать защищённые папки."),
    "problem_not_found": ("Folder not found", "Папка не найдена"),
    "problem_not_dir": ("This is not a folder", "Это не папка"),
    "problem_failed": ("Could not read the folder", "Не удалось прочитать папку"),
    "prop_size": ("Size", "Размер"),
    "prop_calculating": ("Calculating…", "Считаем…"),
    "prop_contents": ("Contents", "Содержимое"),
    "prop_contents_value": ("%1$d files, %2$d folders", "Файлов: %1$d, папок: %2$d"),
    "prop_modified": ("Modified", "Изменён"),
    "prop_link_target": ("Link target", "Цель ссылки"),
    "prop_owner": ("Owner : group", "Владелец : группа"),
    "prop_selinux": ("SELinux context", "Контекст SELinux"),
    "prop_permissions": ("Permissions (octal)", "Права (восьмеричные)"),
    "prop_recursive": ("Apply to everything inside", "Применить ко всему внутри"),
    "prop_mount": ("Mounted at", "Точка монтирования"),
    "prop_checksums": ("Checksums", "Контрольные суммы"),
    "mount_readonly": ("read-only", "только чтение"),
    "mount_readwrite": ("read/write", "чтение и запись"),
    "archive_subtitle": ("%1$s · %2$d files · %3$s", "%1$s · файлов: %2$d · %3$s"),
    "info_format": ("Format", "Формат"),
    "info_files": ("Files", "Файлов"),
    "info_unpacked": ("Unpacked size", "Размер после распаковки"),
    "info_encrypted": ("Encrypted", "Зашифрован"),
    "info_editable": ("Can be edited", "Можно редактировать"),
    "task_copying": ("Copying", "Копирование"),
    "task_moving": ("Moving", "Перемещение"),
    "task_deleting": ("Deleting", "Удаление"),
    "task_compressing": ("Creating archive", "Создание архива"),
    "task_extracting": ("Extracting", "Распаковка"),
    "task_testing": ("Testing archive", "Проверка архива"),
    "task_cancelled": ("Cancelled", "Отменено"),
    "task_failed": ("Failed: %1$s", "Ошибка: %1$s"),
    "result_archive_created": ("Created %1$s", "Создан архив %1$s"),
    "result_permissions_changed": ("Permissions changed", "Права изменены"),
    "result_remounted_rw": ("Mounted read/write", "Смонтировано для записи"),
    "result_remounted_ro": ("Mounted read-only", "Смонтировано только для чтения"),
    "result_saved": ("Saved", "Сохранено"),
    "result_test_ok": ("Archive is intact", "Архив не повреждён"),
    "error_name_empty": ("Enter a name", "Введите имя"),
    "error_name_invalid": ("The name cannot contain “/”", "Имя не может содержать «/»"),
    "error_name_reserved": ("This name is reserved", "Это имя зарезервировано"),
    "error_name_too_long": ("The name is too long", "Имя слишком длинное"),
    "error_name_exists": ("An item with this name already exists", "Элемент с таким именем уже существует"),
    "error_cannot_open": ("Cannot open %1$s", "Не удалось открыть %1$s"),
    "error_no_app": ("No app can open this file", "Нет приложения для открытия этого файла"),
    "error_share_folders": ("Folders cannot be shared. Compress them first.", "Папки нельзя отправить. Сначала сожмите их."),
    "error_file_too_large": ("The file is too large for the editor", "Файл слишком большой для редактора"),
    "error_password_required": ("This archive is password protected", "Архив защищён паролем"),
    "error_wrong_password": ("Wrong password", "Неверный пароль"),
    "error_archive_unsupported": ("Unsupported archive: %1$s", "Формат не поддерживается: %1$s"),
    "error_archive_corrupt": ("The archive is damaged or not an archive", "Архив повреждён или это не архив"),
    "settings_title": ("Settings", "Настройки"),
    "settings_appearance": ("Appearance", "Оформление"),
    "settings_files": ("Files", "Файлы"),
    "settings_root": ("Root", "Root"),
    "settings_archives": ("Archives", "Архивы"),
    "settings_about": ("About", "О приложении"),
    "theme_system": ("System", "Системная"),
    "theme_light": ("Light", "Светлая"),
    "theme_dark": ("Dark", "Тёмная"),
    "setting_dynamic": ("Dynamic color", "Динамические цвета"),
    "setting_dynamic_hint": ("Use colors from your wallpaper", "Цвета из обоев"),
    "setting_amoled": ("Pure black", "Чисто чёрная тема"),
    "setting_amoled_hint": ("True black background in dark theme", "Чёрный фон в тёмной теме"),
    "setting_hidden": ("Show hidden files", "Скрытые файлы"),
    "setting_hidden_hint": ("Files and folders that start with a dot", "Файлы и папки, имена которых начинаются с точки"),
    "setting_confirm_delete": ("Confirm deletion", "Подтверждать удаление"),
    "setting_confirm_delete_hint": ("Ask before deleting anything", "Спрашивать перед удалением"),
    "setting_zip_charset": ("ZIP file name encoding", "Кодировка имён в ZIP"),
    "setting_zip_charset_hint": ("For old archives with garbled names. UTF-8 is right for almost all.", "Для старых архивов с «кракозябрами» в именах. UTF-8 подходит почти всегда."),
    "about_title": ("About File Manager X", "О File Manager X"),
    "about_version": ("Version %1$s", "Версия %1$s"),
    "about_free": ("Free, with no ads and no premium tier. If it saves you time, you can support its development.", "Бесплатно, без рекламы и без премиума. Если приложение экономит вам время, можно поддержать разработку."),
    "about_libraries": ("Built with", "Использованные библиотеки"),
    "about_licenses": ("Fonts and icons", "Шрифты и иконки"),
    "welcome_title": ("Your whole file system", "Вся файловая система"),
    "welcome_body": ("File Manager X needs access to all files to show and manage them. Nothing leaves your device.", "File Manager X нужен доступ ко всем файлам, чтобы показывать их и управлять ими. Ничего не покидает устройство."),
    "welcome_grant": ("Allow access to all files", "Разрешить доступ ко всем файлам"),
    "welcome_skip": ("Continue without it", "Продолжить без него"),
    "action_analyze": ("Analyze storage", "Анализ занятого места"),
    "action_batch_rename": ("Rename all", "Переименовать все"),
    "action_clear": ("Clear", "Очистить"),
    "action_delete_forever": ("Delete forever", "Удалить навсегда"),
    "action_empty_trash": ("Empty trash", "Очистить корзину"),
    "action_find_duplicates": ("Find duplicates", "Найти дубликаты"),
    "action_move_to_trash": ("Move to trash", "В корзину"),
    "action_pin": ("Pin folder", "Закрепить папку"),
    "action_unpin": ("Unpin folder", "Открепить папку"),
    "action_remount_rw_timed": ("Read/write for %1$d min", "Запись на %1$d мин"),
    "action_restore": ("Restore", "Восстановить"),
    "analyzer_other": ("Everything else", "Остальное"),
    "analyzer_title": ("Storage analyzer", "Анализ диска"),
    "analyzer_total": ("%1$s in total", "Всего %1$s"),
    "dialog_batch_rename": ("Rename %1$d items", "Переименовать: %1$d"),
    "dialog_delete_forever_message": ("The selected items will be deleted for good. This cannot be undone.", "Выбранное будет удалено безвозвратно. Отменить это нельзя."),
    "dialog_delete_forever_title": ("Delete forever?", "Удалить навсегда?"),
    "dialog_empty_trash_message": ("Everything in the trash will be deleted for good.", "Всё содержимое корзины будет удалено безвозвратно."),
    "dialog_trash_title": ("Move to trash?", "Переместить в корзину?"),
    "duplicates_group": ("%1$d copies · %2$s each", "Копий: %1$d · по %2$s"),
    "duplicates_none": ("No duplicates found", "Дубликатов нет"),
    "duplicates_phase_hashing": ("Comparing file contents…", "Сравниваю содержимое файлов…"),
    "duplicates_phase_listing": ("Looking through folders…", "Просматриваю папки…"),
    "duplicates_phase_sampling": ("Checking file starts…", "Проверяю начала файлов…"),
    "duplicates_select_extra": ("Keep one", "Оставить по одному"),
    "duplicates_selected": ("%1$d selected · %2$s", "Выбрано %1$d · %2$s"),
    "duplicates_summary": ("%1$s can be freed", "Можно освободить %1$s"),
    "duplicates_title": ("Duplicates", "Дубликаты"),
    "home_pinned": ("Pinned", "Закреплённые"),
    "home_recent": ("Recent", "Недавние"),
    "home_tools": ("Tools", "Инструменты"),
    "notification_channel_tasks": ("File operations", "Операции с файлами"),
    "notification_more_tasks": ("+%1$d more", "Ещё %1$d"),
    "rename_find": ("Find", "Найти"),
    "rename_keep_extension": ("Keep extension", "Сохранять расширение"),
    "rename_mode_pattern": ("Pattern", "Шаблон"),
    "rename_mode_replace": ("Replace", "Замена"),
    "rename_more": ("…and %1$d more", "…и ещё %1$d"),
    "rename_padding": ("Digits", "Цифр"),
    "rename_preview": ("Preview", "Предпросмотр"),
    "rename_problem_duplicate": ("Same name as another item in the list", "Такое же имя у другого элемента списка"),
    "rename_problem_pattern": ("Invalid regular expression", "Некорректное регулярное выражение"),
    "rename_regex": ("Regular expression", "Регулярное выражение"),
    "rename_replace_with": ("Replace with", "Заменить на"),
    "rename_start": ("Start at", "Начать с"),
    "rename_template": ("Name template", "Шаблон имени"),
    "rename_tokens": ("{n} counter · {name} name · {ext} extension · {date} date", "{n} номер · {name} имя · {ext} расширение · {date} дата"),
    "result_remounted_temporarily": ("Read/write for %1$d min, then read-only again", "Запись включена на %1$d мин, потом снова только чтение"),
    "setting_thumbnails": ("Thumbnails", "Миниатюры"),
    "setting_thumbnails_hint": ("Previews for images and videos", "Превью для картинок и видео"),
    "setting_use_trash": ("Trash", "Корзина"),
    "setting_use_trash_hint": ("Deleted files wait 30 days before they are gone", "Удалённое лежит 30 дней, потом исчезает"),
    "task_restoring": ("Restoring…", "Восстановление…"),
    "trash_empty": ("The trash is empty", "Корзина пуста"),
    "trash_hint": ("Items are removed automatically after %1$d days", "Элементы удаляются автоматически через %1$d дн."),
    "trash_title": ("Trash", "Корзина"),
}

# plural name -> (en {quantity: text}, ru {quantity: text})
P = {
    "selected_count": ({"one": "%d selected", "other": "%d selected"},
                       {"one": "Выбран %d", "few": "Выбрано %d", "many": "Выбрано %d", "other": "Выбрано %d"}),
    "clipboard_copied": ({"one": "%d item copied", "other": "%d items copied"},
                         {"one": "Скопирован %d элемент", "few": "Скопировано %d элемента", "many": "Скопировано %d элементов", "other": "Скопировано %d элемента"}),
    "clipboard_cut": ({"one": "%d item to move", "other": "%d items to move"},
                      {"one": "К перемещению: %d элемент", "few": "К перемещению: %d элемента", "many": "К перемещению: %d элементов", "other": "К перемещению: %d элемента"}),
    "dialog_delete_message": ({"one": "Delete %d item permanently?", "other": "Delete %d items permanently?"},
                              {"one": "Удалить %d элемент навсегда?", "few": "Удалить %d элемента навсегда?", "many": "Удалить %d элементов навсегда?", "other": "Удалить %d элемента навсегда?"}),
    "dialog_conflict_message": ({"one": "%d item already exists in this folder.", "other": "%d items already exist in this folder."},
                                {"one": "%d элемент уже есть в этой папке.", "few": "%d элемента уже есть в этой папке.", "many": "%d элементов уже есть в этой папке.", "other": "%d элемента уже есть в этой папке."}),
    "dialog_delete_entries_message": ({"one": "Remove %d item from the archive? This cannot be undone.", "other": "Remove %d items from the archive? This cannot be undone."},
                                      {"one": "Удалить %d элемент из архива? Это нельзя отменить.", "few": "Удалить %d элемента из архива? Это нельзя отменить.", "many": "Удалить %d элементов из архива? Это нельзя отменить.", "other": "Удалить %d элемента из архива? Это нельзя отменить."}),
    "result_copied": ({"one": "Copied %d item", "other": "Copied %d items"},
                      {"one": "Скопирован %d элемент", "few": "Скопировано %d элемента", "many": "Скопировано %d элементов", "other": "Скопировано %d элемента"}),
    "result_moved": ({"one": "Moved %d item", "other": "Moved %d items"},
                     {"one": "Перемещён %d элемент", "few": "Перемещено %d элемента", "many": "Перемещено %d элементов", "other": "Перемещено %d элемента"}),
    "dialog_trash_message": ({"one": "%d item will be moved to the trash.", "other": "%d items will be moved to the trash."},
                             {"one": "%d элемент будет перемещён в корзину.", "few": "%d элемента будут перемещены в корзину.", "many": "%d элементов будут перемещены в корзину.", "other": "%d элемента будут перемещены в корзину."}),
    "result_renamed": ({"one": "Renamed %d item", "other": "Renamed %d items"},
                       {"one": "Переименован %d элемент", "few": "Переименовано %d элемента", "many": "Переименовано %d элементов", "other": "Переименовано %d элемента"}),
    "result_restored": ({"one": "Restored %d item", "other": "Restored %d items"},
                        {"one": "Восстановлен %d элемент", "few": "Восстановлено %d элемента", "many": "Восстановлено %d элементов", "other": "Восстановлено %d элемента"}),
    "result_trashed": ({"one": "%d item moved to the trash", "other": "%d items moved to the trash"},
                       {"one": "%d элемент в корзине", "few": "%d элемента в корзине", "many": "%d элементов в корзине", "other": "%d элемента в корзине"}),
    "result_deleted": ({"one": "Deleted %d item", "other": "Deleted %d items"},
                       {"one": "Удалён %d элемент", "few": "Удалено %d элемента", "many": "Удалено %d элементов", "other": "Удалено %d элемента"}),
    "result_entries_removed": ({"one": "Removed %d item from the archive", "other": "Removed %d items from the archive"},
                               {"one": "Из архива удалён %d элемент", "few": "Из архива удалено %d элемента", "many": "Из архива удалено %d элементов", "other": "Из архива удалено %d элемента"}),
    "result_extracted": ({"one": "Extracted %1$d file to %2$s", "other": "Extracted %1$d files to %2$s"},
                         {"one": "Распакован %1$d файл в «%2$s»", "few": "Распаковано %1$d файла в «%2$s»", "many": "Распаковано %1$d файлов в «%2$s»", "other": "Распаковано %1$d файла в «%2$s»"}),
}


def esc(text: str) -> str:
    text = escape(text).replace("'", "\\'").replace('"', '\\"')
    if text.startswith("@") or text.startswith("?"):
        text = "\\" + text
    return text


def write(path: Path, idx: int) -> None:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, pair in S.items():
        lines.append(f'    <string name="{name}">{esc(pair[idx])}</string>')
    for name, pair in P.items():
        lines.append(f'    <plurals name="{name}">')
        for quantity, text in pair[idx].items():
            lines.append(f'        <item quantity="{quantity}">{esc(text)}</item>')
        lines.append("    </plurals>")
    lines.append("</resources>")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def check() -> bool:
    used_s, used_p = set(), set()
    for file in (SRC / "java").rglob("*.kt"):
        text = file.read_text(encoding="utf-8")
        used_s |= set(re.findall(r"R\.string\.([a-z_0-9]+)", text))
        used_p |= set(re.findall(r"R\.plurals\.([a-z_0-9]+)", text))
    manifest = (SRC / "AndroidManifest.xml").read_text(encoding="utf-8")
    used_s |= set(re.findall(r"@string/([a-z_0-9]+)", manifest))
    ok = True
    for name in sorted(used_s - set(S)):
        print("MISSING string:", name)
        ok = False
    for name in sorted(used_p - set(P)):
        print("MISSING plural:", name)
        ok = False
    for name in sorted(set(S) - used_s):
        print("unused string:", name)
    for name in sorted(set(P) - used_p):
        print("unused plural:", name)
    # Placeholder parity between languages.
    pattern = re.compile(r"%(?:\d\$)?[sd]")
    for name, (en, ru) in S.items():
        if sorted(pattern.findall(en)) != sorted(pattern.findall(ru)):
            print("placeholder mismatch:", name)
            ok = False
    return ok


if __name__ == "__main__":
    write(SRC / "res/values/strings.xml", 0)
    write(SRC / "res/values-ru/strings.xml", 1)
    sys.exit(0 if check() else 1)
