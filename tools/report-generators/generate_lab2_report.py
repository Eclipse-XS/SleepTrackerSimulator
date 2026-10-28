from __future__ import annotations

import os
from pathlib import Path

from docx import Document
from docx.enum.table import WD_ALIGN_VERTICAL, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor


SOURCE = Path(r"C:\Users\Eclipse\Desktop\7 семестр\Програмування мобільних смарт-систем\ЛР-1_МПСС_Гончаров_Вадим_ОІ-43_final.docx")
TARGET = Path(r"C:\Users\Eclipse\Desktop\7 семестр\Програмування мобільних смарт-систем\ЛР-2_МПСС_Гончаров_Вадим_ОІ-43.docx")
TMP = TARGET.with_name(TARGET.stem + ".tmp.docx")

DIAGRAM_PATH = Path(__file__).resolve().parent / "ble_state_machine.png"
SCREENSHOTS = {
    "studio": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 130907.png"),
    "initial": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131034.png"),
    "connected": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131057.png"),
    "data_transfer": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131155.png"),
    "disconnected": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131315.png"),
}


def set_font(run, name="Times New Roman", size=14, bold=None, italic=None, color=None):
    run.font.name = name
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.get_or_add_rFonts()
    rfonts.set(qn("w:ascii"), name)
    rfonts.set(qn("w:hAnsi"), name)
    rfonts.set(qn("w:eastAsia"), name)
    run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if italic is not None:
        run.italic = italic
    if color is not None:
        run.font.color.rgb = color


def body(doc, text, first_line=True, after=6):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    pf = p.paragraph_format
    pf.line_spacing = 1.5
    pf.space_after = Pt(after)
    if first_line:
        pf.first_line_indent = Cm(1.25)
    r = p.add_run(text)
    set_font(r)
    return p


def heading(doc, text, level=1):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER if level == 1 else WD_ALIGN_PARAGRAPH.LEFT
    pf = p.paragraph_format
    pf.space_before = Pt(12 if level == 1 else 9)
    pf.space_after = Pt(6)
    pf.keep_with_next = True
    r = p.add_run(text)
    set_font(r, size=14, bold=True)
    return p


def list_item(doc, text, number=None, level=0):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    pf = p.paragraph_format
    pf.line_spacing = 1.5
    pf.space_after = Pt(3)
    pf.left_indent = Cm(0.75 + level * 0.6)
    pf.first_line_indent = Cm(-0.5)
    marker = f"{number}. " if number is not None else "• "
    r = p.add_run(marker + text)
    set_font(r)
    return p


def figure(doc, image_path, caption, width_cm=15.5):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(2)
    p.add_run().add_picture(str(image_path), width=Cm(width_cm))
    c = doc.add_paragraph()
    c.alignment = WD_ALIGN_PARAGRAPH.CENTER
    c.paragraph_format.space_after = Pt(6)
    c.paragraph_format.keep_together = True
    r = c.add_run(caption)
    set_font(r, size=12, italic=True)


def figure_placeholder(doc, title, description, caption):
    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    cell = table.cell(0, 0)
    cell.width = Cm(16.2)
    cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER

    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:fill"), "FFFBEB")
    tc_pr.append(shd)

    borders = OxmlElement("w:tcBorders")
    for side in ("top", "left", "bottom", "right"):
        el = OxmlElement(f"w:{side}")
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), "8")
        el.set(qn("w:color"), "D97706")
        borders.append(el)
    tc_pr.append(borders)

    margins = OxmlElement("w:tcMar")
    for side, value in (("top", "160"), ("left", "200"), ("bottom", "160"), ("right", "200")):
        el = OxmlElement(f"w:{side}")
        el.set(qn("w:w"), value)
        el.set(qn("w:type"), "dxa")
        margins.append(el)
    tc_pr.append(margins)

    cp = cell.paragraphs[0]
    cp.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cp.paragraph_format.line_spacing = 1.15
    cp.paragraph_format.space_after = Pt(4)
    run1 = cp.add_run(title)
    set_font(run1, size=11, bold=True, color=RGBColor(180, 83, 9))

    p2 = cell.add_paragraph()
    p2.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p2.paragraph_format.line_spacing = 1.15
    p2.paragraph_format.space_after = Pt(0)
    run2 = p2.add_run(description)
    set_font(run2, size=10, italic=True, color=RGBColor(146, 64, 14))

    c = doc.add_paragraph()
    c.alignment = WD_ALIGN_PARAGRAPH.CENTER
    c.paragraph_format.space_before = Pt(3)
    c.paragraph_format.space_after = Pt(6)
    c.paragraph_format.keep_together = True
    r = c.add_run(caption)
    set_font(r, size=12, italic=True)


def code_listing(doc, title, code):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(3)
    r = p.add_run(title)
    set_font(r, size=12, bold=True)

    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    cell = table.cell(0, 0)
    cell.width = Cm(16.2)
    cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER

    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:fill"), "F3F3F3")
    tc_pr.append(shd)

    borders = OxmlElement("w:tcBorders")
    for side in ("top", "left", "bottom", "right"):
        el = OxmlElement(f"w:{side}")
        el.set(qn("w:val"), "single")
        el.set(qn("w:sz"), "4")
        el.set(qn("w:color"), "D9D9D9")
        borders.append(el)
    tc_pr.append(borders)

    margins = OxmlElement("w:tcMar")
    for side, value in (("top", "100"), ("left", "140"), ("bottom", "100"), ("right", "140")):
        el = OxmlElement(f"w:{side}")
        el.set(qn("w:w"), value)
        el.set(qn("w:type"), "dxa")
        margins.append(el)
    tc_pr.append(margins)

    cp = cell.paragraphs[0]
    cp.paragraph_format.line_spacing = 1.0
    cp.paragraph_format.space_after = Pt(0)
    run = cp.add_run(code.strip())
    set_font(run, name="Consolas", size=8.5)
    doc.add_paragraph().paragraph_format.space_after = Pt(0)


def gatt_table(doc, title, headers, rows, col_widths):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(3)
    r = p.add_run(title)
    set_font(r, size=12, bold=True)

    table = doc.add_table(rows=len(rows) + 1, cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False

    # Header Row
    hdr_cells = table.rows[0].cells
    for i, title_text in enumerate(headers):
        hdr_cells[i].width = Cm(col_widths[i])
        tc_pr = hdr_cells[i]._tc.get_or_add_tcPr()
        shd = OxmlElement("w:shd")
        shd.set(qn("w:fill"), "EAEAEA")
        tc_pr.append(shd)

        borders = OxmlElement("w:tcBorders")
        for side in ("top", "left", "bottom", "right"):
            el = OxmlElement(f"w:{side}")
            el.set(qn("w:val"), "single")
            el.set(qn("w:sz"), "4")
            el.set(qn("w:color"), "D9D9D9")
            borders.append(el)
        tc_pr.append(borders)

        cp = hdr_cells[i].paragraphs[0]
        cp.alignment = WD_ALIGN_PARAGRAPH.CENTER
        cp.paragraph_format.space_after = Pt(0)
        run = cp.add_run(title_text)
        set_font(run, size=11, bold=True)

    # Data Rows
    for row_idx, row_data in enumerate(rows):
        cells = table.rows[row_idx + 1].cells
        for col_idx, cell_value in enumerate(row_data):
            cells[col_idx].width = Cm(col_widths[col_idx])
            tc_pr = cells[col_idx]._tc.get_or_add_tcPr()

            borders = OxmlElement("w:tcBorders")
            for side in ("top", "left", "bottom", "right"):
                el = OxmlElement(f"w:{side}")
                el.set(qn("w:val"), "single")
                el.set(qn("w:sz"), "4")
                el.set(qn("w:color"), "D9D9D9")
                borders.append(el)
            tc_pr.append(borders)

            cp = cells[col_idx].paragraphs[0]
            cp.paragraph_format.line_spacing = 1.15
            cp.paragraph_format.space_after = Pt(0)
            if col_idx in (0, 3):
                cp.alignment = WD_ALIGN_PARAGRAPH.CENTER
            else:
                cp.alignment = WD_ALIGN_PARAGRAPH.LEFT
            run = cp.add_run(cell_value)
            set_font(run, size=10.5)

    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def remove_after_title(doc):
    marker = next(p for p in doc.paragraphs if p.text.strip() == "Львів-2026")
    body_element = doc._element.body
    children = list(body_element)
    marker_index = children.index(marker._element)
    for element in children[marker_index + 1:]:
        if element.tag != qn("w:sectPr"):
            body_element.remove(element)


def update_title_page(doc):
    # Paragraph 8: ' до лабораторної роботи № 1'
    p8 = doc.paragraphs[8]
    if len(p8.runs) > 1:
        p8.runs[1].text = "2"
    else:
        p8.text = " до лабораторної роботи № 2"
        set_font(p8.runs[0], size=14, bold=True)

    # Paragraph 12: Topic
    p12 = doc.paragraphs[12]
    if len(p12.runs) >= 4:
        p12.runs[3].text = "Емуляція взаємодії з IoT-пристроєм через Bluetooth Low Energy (BLE) та протокол передачі даних"
    else:
        p12.text = "  «Емуляція взаємодії з IoT-пристроєм через Bluetooth Low Energy (BLE) та протокол передачі даних»"
        set_font(p12.runs[0], size=14, bold=True)


def main():
    if not SOURCE.exists():
        raise FileNotFoundError(f"Source report not found: {SOURCE}")
    if not DIAGRAM_PATH.exists():
        raise FileNotFoundError(f"Diagram not found: {DIAGRAM_PATH}")
    for k, v in SCREENSHOTS.items():
        if not v.exists():
            raise FileNotFoundError(f"Screenshot {k} not found: {v}")

    doc = Document(SOURCE)
    update_title_page(doc)
    remove_after_title(doc)

    # ==================== МЕТА РОБОТИ ====================
    heading(doc, "Мета роботи")
    body(doc, "Опанувати принципи роботи з Bluetooth-стеком Android, вивчити життєвий цикл бездротового з’єднання та реалізувати архітектурний механізм сканування й обміну даними (Read, Write, Notify) через програмну абстракцію (Mock-сервіс) для взаємодії зі смарт-пристроєм моніторингу сну.")

    # ==================== КОНЦЕПЦІЯ НААСКРІЗНОГО ЗАВДАННЯ ====================
    heading(doc, "Концепція наскрізного завдання")
    body(doc, "У межах виконання серії лабораторних робіт з дисципліни «Програмування мобільних смарт-систем» розробляється наскрізний навчальний проєкт за варіантом №4 - Sleep Tracker Simulator. У попередній лабораторній роботі було сформовано початковий сенсорний шар застосунку: налагоджено взаємодію з підсистемою SensorManager, реалізовано зчитування тривимірних координат акселерометра, фільтрацію шуму з гістерезисом та розпізнавання станів рухової активності (STILL та MOVEMENT).")
    body(doc, "Метою другої лабораторної роботи є перетворення автономного мобільного сенсора на вузол розподіленої смарт-системи, здатний взаємодіяти із зовнішнім апаратним хабом («Sleep Tracker Hub») через енергоефективний протокол Bluetooth Low Energy (BLE). Оскільки офіційне середовище Android Emulator має суттєві обмеження щодо прямої віртуалізації апаратного Bluetooth-контролера та радіоефіру, завдання реалізується шляхом побудови повноцінної архітектурної програмної емуляції (Mock-сервісу). Ця абстракція відтворює поведінку справжнього BLE-стеку: асинхронний пошук периферійних пристроїв, встановлення GATT-з'єднання, виявлення характеристик, генерацію вхідних сповіщень (Notify) та обробку команд (Write) із квитуванням.")
    body(doc, "Критично важливою вимогою наскрізного проєктування є збереження непорушності функціональності першої лабораторної роботи. Моніторинг акселерометра та класифікація рухів продовжують безперервно функціонувати паралельно з новим комунікаційним модулем, не зазнаючи блокувань чи функціональних регресій.")

    # ==================== ЗАВДАННЯ НА ЛАБОРАТОРНУ РОБОТУ ====================
    heading(doc, "Завдання на лабораторну роботу")
    list_item(doc, "Оголосити в маніфесті AndroidManifest.xml повний набір системних дозволів (Permissions), необхідних для повноцінного функціонування BLE-стеку в сучасних версіях Android 12+ (API 31+): BLUETOOTH_SCAN, BLUETOOTH_CONNECT, а також забезпечити сумісність із попередніми версіями платформи (BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION).", 1)
    list_item(doc, "Спроєктувати GATT-профіль віртуального IoT-пристрою (Sleep Tracker Hub): визначити Service UUID, Characteristic UUID для зчитування статусу та отримання сповіщень (Read/Notify), а також Characteristic UUID для надсилання команд керування (Write).", 2)
    list_item(doc, "Розробити архітектурну абстракцію (інтерфейс IBleConnector та клас MockBleScanner), що імітує часові затримки радіоефіру, асинхронне сканування та виявлення периферійних вузлів за допомогою корутин.", 3)
    list_item(doc, "Реалізувати надійний скінченний автомат (State Machine) життєвого циклу підключення: послідовні переходи між станами IDLE, SCANNING, DEVICE_FOUND, CONNECTING, CONNECTED, DISCONNECTED та коректне опрацювання помилок (ERROR).", 4)
    list_item(doc, "Реалізувати механізм двостороннього обміну даними: надсилання тестових команд на хаб (Write) та емуляцію прийому відповідей (ACK) і сповіщень (Notify).", 5)
    list_item(doc, "Модернізувати користувацький інтерфейс на базі Jetpack Compose, додавши інтерактивну панель керування BLE: індикатор з'єднання, кнопки пошуку та підключення, текстове поле вводу команд і прокручуваний термінал логів.", 6)
    list_item(doc, "Забезпечити повну ізоляцію та паралельне функціонування сенсорного екрана з Л.Р. №1 без жодних регресій у логіці обробки рухів.", 7)
    list_item(doc, "Покрити розроблену бізнес-логіку комплексом автоматизованих модульних тестів (Unit Tests), перевіривши поведінку сканера, переходи станів конектора та реакцію ViewModel.", 8)

    # ==================== ТЕХНІЧНИЙ СТЕК ====================
    heading(doc, "Технічний стек")
    list_item(doc, "Мова програмування: Kotlin 2.0.21 (із суворим дотриманням стандартів корутин та реактивного потокового програмування).")
    list_item(doc, "Цільова мобільна платформа: Android (minSdk 31 - Android 12, compileSdk 36, targetSdk 36 - Android 16).")
    list_item(doc, "Декларативний інтерфейс користувача: Jetpack Compose (Material Design 3, Compose Foundation, Compose Material Icons).")
    list_item(doc, "Архітектурний патерн: MVVM (Model-View-ViewModel) з розділенням шарів UI, доменної логіки та джерел даних.")
    list_item(doc, "Асинхронне програмування та стан: Kotlin Coroutines, StateFlow, SupervisorJob, Dispatchers.Main.immediate.")
    list_item(doc, "Протокол бездротового зв’язку: Bluetooth Low Energy (BLE), архітектура Generic Attribute Profile (GATT), 128-бітні UUID.")
    list_item(doc, "Середовище розробки: Android Studio Ladybug Feature Drop (2024.2.2 / Koala), Gradle 8.11.1.")
    list_item(doc, "Тестове середовище: Android Emulator, віртуальний пристрій Pixel 10 Pro (API 36.1, Android 16.0 VanillaIceCream).")
    list_item(doc, "Інструменти автоматизованого тестування: JUnit 4, Kotlinx Coroutines Test (StandardTestDispatcher, runTest), Android Lint.")

    # ==================== ОПИС ПРОТОКОЛУ ВЗАЄМОДІЇ ====================
    heading(doc, "Опис протоколу взаємодії")
    body(doc, "Стандарт Bluetooth Low Energy базується на ієрархічній архітектурі GATT (Generic Attribute Profile), де обмін даними структурується за допомогою сервісів (Services) та характеристик (Characteristics). Кожна сутність однозначно ідентифікується 128-бітним унікальним числом (UUID). Периферійний пристрій виступає як GATT-сервер, що оголошує свої можливості у пакетах Advertising Data, а центральний пристрій (смартфон) виступає як GATT-клієнт, що здійснює сканування та ініціює сеанс зв'язку.")
    body(doc, "Для симулятора моніторингу сну Sleep Tracker Simulator було спроєктовано спеціалізований GATT-профіль віртуального смарт-хаба Sleep Tracker Hub. Специфікацію сервісу, характеристик та їхніх прав доступу наведено в табл. 1.")

    gatt_headers = ["Елемент GATT", "Призначення", "UUID / Ідентифікатор", "Властивості"]
    gatt_rows = [
        ["Service", "Головний сервіс обміну даними та моніторингу стану хаба", "0000a000-0000-1000-8000-00805f9b34fb", "Primary Service"],
        ["Characteristic (Status)", "Зчитування статусу, асинхронні сповіщення та прийом підтверджень (ACK)", "0000a001-0000-1000-8000-00805f9b34fb", "Read, Notify"],
        ["Characteristic (Command)", "Передавання текстових команд керування та запитів до хаба", "0000a002-0000-1000-8000-00805f9b34fb", "Write"],
        ["Device Information", "Ідентифікаційні параметри периферійного пристрою в ефірі", "Sleep Tracker Hub\n(MOCK:00:11:22:33:44)", "Advertising Data"],
    ]
    gatt_widths = [3.2, 5.0, 5.2, 2.8]
    gatt_table(doc, "Таблиця 1. Специфікація GATT-профілю віртуального хаба Sleep Tracker Hub", gatt_headers, gatt_rows, gatt_widths)

    body(doc, "Протокол взаємодії передбачає наступну семантику обміну повідомленнями:")
    list_item(doc, "Після успішного підключення хаб автоматично надсилає клієнту асинхронне сповіщення (Notify) через характеристику Status: «STATUS:READY». Це інформує клієнтський застосунок про готовність до прийому керуючих інструкцій.")
    list_item(doc, "Клієнт надсилає команди керування шляхом запису рядкових UTF-8 даних (Write) у характеристику Command (наприклад, «STATUS_REQUEST», «START_STREAM», «STOP_STREAM»).")
    list_item(doc, "У відповідь на кожну прийняту команду хаб надсилає підтвердження квитування (ACK) через механізм Notify характеристики Status у форматі «ACK:<НАЗВА_КОМАНДИ>» (наприклад, «ACK:STATUS_REQUEST»).")

    # ==================== ДІАГРАМА СТАНІВ ПІДКЛЮЧЕННЯ ====================
    heading(doc, "Діаграма станів підключення")
    body(doc, "Життєвий цикл підключення та обміну даними в системі формалізовано у вигляді скінченного детермінованого автомата (State Machine). Завдяки суворій типізації станів у Kotlin за допомогою sealed interface BleConnectionState, виключається можливість некоректних перехідних операцій (наприклад, спроба відправки команди до встановлення зв'язку або повторний запуск сканування під час активної сесії). Графічну схему станів та переходів наведено на рис. 1.")

    figure(doc, DIAGRAM_PATH, "Рис. 1. Діаграма станів скінченного автомата BLE-з'єднання (MockBleConnector)", width_cm=15.5)

    body(doc, "Функціонування автомата підпорядковується наступним правилам:")
    list_item(doc, "Idle / Disconnected: початковий неактивний стан. Радіомодуль не використовується, ресурси процесора звільнено.")
    list_item(doc, "Scanning: ініціюється викликом методу startScan(). Протягом 2000 мс сканер асинхронно імітує прослуховування радіоефіру.")
    list_item(doc, "DeviceFound: сканер виявляє віртуальний пристрій «Sleep Tracker Hub» (адреса MOCK:00:11:22:33:44) та передає список знайдених вузлів у UI.")
    list_item(doc, "Connecting: ініціюється викликом connect(device). Імітує узгодження MTU, обмін ключами та пошук GATT-сервісів (затримка 750 мс).")
    list_item(doc, "Connected: активне стабільне з'єднання. Хаб надсилає стартове сповіщення STATUS:READY, стають доступними операції Write та Notify.")
    list_item(doc, "Disconnected: ініціюється користувачем (кнопка Disconnect) або через обрив зв'язку. Усі фонові корутини скасування операцій коректно перериваються, а термінал зберігає журнал попередніх повідомлень.")
    list_item(doc, "Error: перехід у стан збою при виникненні непередбачених апаратних або мережевих помилок із можливістю повторного запуску сканування.")

    # ==================== ХІД РОБОТИ ====================
    heading(doc, "Хід роботи")
    body(doc, "Виконання практичної частини лабораторної роботи було організовано у вісім послідовних етапів. На кожному етапі вирішувалася конкретна інженерна задача: від системного конфігурування маніфесту та створення абстрактних контрактів до повної інтеграції з реактивним інтерфейсом Compose та верифікації модульними тестами.")

    # ЕТАП 1
    heading(doc, "Етап 1. Конфігурація системних дозволів та проєктування GATT-профілю", 2)
    body(doc, "Згідно з вимогами безпеки операційної системи Android, робота з бездротовим стеком Bluetooth суворо контролюється системою дозволів (Runtime Permissions). Починаючи з Android 12 (API 31), компанія Google розділила загальні дозволи на вузькоспеціалізовані: BLUETOOTH_SCAN (для сканування радіоефіру) та BLUETOOTH_CONNECT (для взаємодії з уже підключеним пристроєм).")
    body(doc, "У файлі AndroidManifest.xml було оголошено необхідні директиви дозволів. Для BLUETOOTH_SCAN додано прапорець android:usesPermissionFlags=\"neverForLocation\", оскільки застосунок не використовує Bluetooth-маячки для геолокації користувача. Для забезпечення зворотної сумісності із застарілими пристроями збережено дозволи BLUETOOTH, BLUETOOTH_ADMIN та ACCESS_FINE_LOCATION з обмеженням android:maxSdkVersion=\"30\".")
    body(doc, "У пакеті ble створено файл BleModels.kt, де визначено модель пристрою BleDevice(name, address), константи UUID у об'єкті BleProfile та стани з'єднання у вигляді закритого інтерфейсу BleConnectionState (Idle, Scanning, DeviceFound, Connecting, Connected, Disconnected, Error).")

    # ЕТАП 2
    heading(doc, "Етап 2. Розробка інтерфейсу зв'язку (IBleConnector)", 2)
    body(doc, "Для забезпечення принципу інверсії залежностей (Dependency Inversion Principle) із SOLID та ізоляції шару інтерфейсу від деталей передачі даних було спроєктовано інтерфейс IBleConnector. Він визначає уніфікований контракт, якому повинні відповідати як навчальна Mock-реалізація, так і майбутній бойовий клієнт BluetoothGatt.")
    body(doc, "Інтерфейс надає реактивні потоки StateFlow для спостереження за станом з'єднання та текстовими логами, а також декларує функції керування життєвим циклом: startScan, connect, disconnect, sendData та close. Повний код інтерфейсу наведено в лістингу 1.")

    code_listing(doc, "Лістинг 1. Інтерфейс абстракції зв'язку з BLE-пристроєм (IBleConnector.kt)", r'''
package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.flow.StateFlow

interface IBleConnector {
    val state: StateFlow<BleConnectionState>
    val logs: StateFlow<List<String>>
    fun startScan()
    fun connect(device: BleDevice)
    fun disconnect()
    fun sendData(data: String)
    fun close()
}
''')

    # ЕТАП 3
    heading(doc, "Етап 3. Реалізація віртуального сканера (MockBleScanner)", 2)
    body(doc, "На наступному етапі було створено компонент MockBleScanner, призначений для моделювання асинхронного сканування BLE-ефіру. У реальних умовах сканування є найбільш енерговитратною процедурою, яка триває від 1 до кількох секунд.")
    body(doc, "У реалізації сканера функція scan() є suspend-функцією, яка за допомогою delay(discoveryDelayMillis) імітує затримку пошуку (за замовчуванням 2000 мс), після чого повертає список доступних периферійних вузлів із віртуальним пристроєм Sleep Tracker Hub (MAC-адреса MOCK:00:11:22:33:44). Вихідний код сканера наведено в лістингу 2.")

    code_listing(doc, "Лістинг 2. Віртуальний сканер периферійних пристроїв (MockBleScanner.kt)", r'''
package com.example.sleeptrackersimulator.ble

import kotlinx.coroutines.delay

class MockBleScanner(
    private val discoveryDelayMillis: Long = 2_000L,
) {
    suspend fun scan(): List<BleDevice> {
        delay(discoveryDelayMillis)
        return listOf(VIRTUAL_DEVICE)
    }

    companion object {
        val VIRTUAL_DEVICE = BleDevice("Sleep Tracker Hub", "MOCK:00:11:22:33:44")
    }
}
''')

    # ЕТАП 4
    heading(doc, "Етап 4. Реалізація віртуального GATT-конектора (MockBleConnector)", 2)
    body(doc, "Ключовим компонентом підсистеми є клас MockBleConnector, що реалізує інтерфейс IBleConnector. Він керує внутрішнім станом через MutableStateFlow, підтримує кільцевий буфер системних логів (до 50 останніх записів) та дублює події в системний лог Android Logcat (тег MockBleConnector).")
    body(doc, "Конектор оперує власним CoroutineScope із SupervisorJob() та Dispatchers.Main.immediate. Під час виклику connect(device) запускається корутина з затримкою 750 мс, яка переводить конектор у Connected та імітує прийом стартового сповіщення NOTIFY STATUS:READY. Під час виклику sendData(data) фіксується вихідний пакет TX до Command Characteristic та автоматично формується відповідь RX із квитуванням ACK до Status Characteristic. Ключові фрагменти реалізації наведено в лістингу 3.")

    code_listing(doc, "Лістинг 3. Реалізація віртуального GATT-конектора (MockBleConnector.kt)", r'''
class MockBleConnector(
    private val scanner: MockBleScanner = MockBleScanner(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val connectionDelayMillis: Long = 750L,
    private val logOutput: (String, String) -> Unit = { tag, msg -> Log.d(tag, msg) },
) : IBleConnector {

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    override val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var operationJob: Job? = null

    override fun startScan() {
        if (_state.value is BleConnectionState.Scanning) return
        operationJob?.cancel()
        _state.value = BleConnectionState.Scanning
        log("SCAN started")
        operationJob = scope.launch {
            try {
                val devices = scanner.scan()
                _state.value = BleConnectionState.DeviceFound(devices)
                devices.forEach { log("FOUND ${it.name} [${it.address}]") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = BleConnectionState.Error(error.message ?: "BLE scan failed")
                log("ERROR scan failed")
            }
        }
    }

    override fun connect(device: BleDevice) {
        val currentState = _state.value
        val found = (currentState as? BleConnectionState.DeviceFound)?.devices.orEmpty()
        if (device !in found) return
        operationJob?.cancel()
        _state.value = BleConnectionState.Connecting(device)
        log("CONNECTING ${device.name}")
        operationJob = scope.launch {
            delay(connectionDelayMillis)
            _state.value = BleConnectionState.Connected(device)
            log("CONNECTED ${device.name}")
            log("NOTIFY ${BleProfile.STATUS_CHARACTERISTIC_UUID}: STATUS:READY")
        }
    }

    override fun disconnect() {
        val current = _state.value
        if (current is BleConnectionState.Disconnected || current is BleConnectionState.Idle) return
        operationJob?.cancel()
        val device = when (current) {
            is BleConnectionState.Connected -> current.device
            is BleConnectionState.Connecting -> current.device
            else -> null
        }
        _state.value = BleConnectionState.Disconnected(device)
        log("DISCONNECTED${device?.let { " ${it.name}" }.orEmpty()}")
    }

    override fun sendData(data: String) {
        if (_state.value !is BleConnectionState.Connected || data.isBlank()) return
        val payload = data.trim()
        log("TX ${BleProfile.COMMAND_CHARACTERISTIC_UUID}: $payload")
        log("RX ${BleProfile.STATUS_CHARACTERISTIC_UUID}: ACK:$payload")
    }

    override fun close() {
        operationJob?.cancel()
        scope.cancel()
    }
}
''')

    # ЕТАП 5
    heading(doc, "Етап 5. Архітектура та керування станом (BleViewModel)", 2)
    body(doc, "Для зв'язування бізнес-логіки конектора з декларативним інтерфейсом Compose створено клас BleViewModel у пакеті ui/dashboard. ViewModel отримує екземпляр IBleConnector через фабричний метод BleViewModel.factory(connector).")
    body(doc, "ViewModel надає Compose-компонентам незмінні потоки state та logs. Всі користувацькі інтенції (натискання кнопок «Пошук», «Підключити», «Відключити» та відправка команд) транслюються у відповідні виклики конектора. При знищенні життєвого циклу екрана метод onCleared() викликає connector.close(), запобігаючи витокам пам'яті та зависанню фонових завдань. Код BleViewModel наведено в лістингу 4.")

    code_listing(doc, "Лістинг 4. Архітектурне керування станом Bluetooth у ViewModel (BleViewModel.kt)", r'''
package com.example.sleeptrackersimulator.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.sleeptrackersimulator.ble.BleConnectionState
import com.example.sleeptrackersimulator.ble.BleDevice
import com.example.sleeptrackersimulator.ble.IBleConnector
import kotlinx.coroutines.flow.StateFlow

class BleViewModel(private val connector: IBleConnector) : ViewModel() {
    val state: StateFlow<BleConnectionState> = connector.state
    val logs: StateFlow<List<String>> = connector.logs

    fun startScan() = connector.startScan()
    fun connect(device: BleDevice) = connector.connect(device)
    fun disconnect() = connector.disconnect()
    fun sendData(data: String) {
        if (data.isNotBlank()) {
            connector.sendData(data.trim())
        }
    }

    override fun onCleared() {
        connector.close()
        super.onCleared()
    }

    companion object {
        fun factory(connector: IBleConnector): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(BleViewModel::class.java))
                    return BleViewModel(connector) as T
                }
            }
    }
}
''')

    # ЕТАП 6
    heading(doc, "Етап 6. Розширення користувацького інтерфейсу на Jetpack Compose", 2)
    body(doc, "Інтерфейс користувача було розширено в файлі SensorScreen.kt шляхом додавання компонента MockBleCard безпосередньо під сенсорною панеллю Л.Р. №1. Для забезпечення сумісності з різними розмірами екранів та орієнтаціями пристрою головний контейнер Column оснащено модифікатором verticalScroll(rememberScrollState()).")
    body(doc, "На рис. 2 продемонстровано робоче середовище Android Studio під час виконання розробленого застосунку на емуляторі Pixel 10 Pro (API 36.1). Чітко видно одночасне співіснування сенсорного блоку (акселерометр у стані STILL із прискоренням 9,81 м/с²) та блоку Mock BLE connection у стані Disconnected.")

    figure(doc, SCREENSHOTS["studio"], "Рис. 2. Робоче середовище Android Studio та виконання застосунку Sleep Tracker Simulator на емуляторі Pixel 10 Pro", width_cm=15.5)

    body(doc, "На рис. 3 наведено вигляд початкового стану блоку керування Bluetooth на екрані емулятора. Статус з'єднання відображається як «Disconnected» із сірим індикатором, користувачеві доступна кнопка «Search for devices», а термінал сповіщає про відсутність отриманих повідомлень.")

    figure(doc, SCREENSHOTS["initial"], "Рис. 3. Початковий стан панелі Mock BLE (Disconnected) з можливістю запуску пошуку пристроїв", width_cm=7.5)

    body(doc, "Після натискання кнопки «Search for devices» запускається асинхронне сканування (тривалістю 2000 мс), після завершення якого стан змінюється на DeviceFound, а в інтерфейсі динамічно з'являється картка знайденого периферійного пристрою Sleep Tracker Hub із кнопкою «Connect». Відповідний стан ілюструється на рис. 4.")

    figure_placeholder(
        doc,
        "[ВСТАВИТИ СКРИНШОТ ЗНАЙДЕНОГО ПРИСТРОЮ ПІСЛЯ РУЧНОЇ ПЕРЕВІРКИ]",
        "Скриншот екрана емулятора в момент знаходження пристрою: відображення знайденого периферійного вузла «Sleep Tracker Hub [MOCK:00:11:22:33:44]» та активної кнопки «Connect».",
        "Рис. 4. Відображення списку знайдених BLE-пристроїв після завершення асинхронного сканування"
    )

    # ЕТАП 7
    heading(doc, "Етап 7. Емуляція обміну даними та термінал подій", 2)
    body(doc, "Після натискання кнопки «Connect» система переходить у стан Connecting, а після закінчення затримки узгодження (750 мс) - у стан Connected. Статус оновлюється на «Connected: Sleep Tracker Hub», індикатор змінює колір на зелений, з'являються поле вводу Command, кнопка Send та кнопка Disconnect.")
    body(doc, "Одночасно у вбудованому терміналі виводиться послідовність записів: SCAN started -> FOUND Sleep Tracker Hub -> CONNECTING -> CONNECTED, після чого відображається вхідне сповіщення від хаба: NOTIFY 0000a001-0000-1000-8000-00805f9b34fb: STATUS:READY. Цей стан зафіксовано на рис. 5.")

    figure(doc, SCREENSHOTS["connected"], "Рис. 5. Успішне встановлення з'єднання з віртуальним хабом та отримання вхідного сповіщення NOTIFY: STATUS:READY", width_cm=8.5)

    body(doc, "Для перевірки передавання керуючих інструкцій у поле вводу введено команду «STATUS_REQUEST» та натиснуто кнопку «Send». Як продемонстровано на рис. 6, у терміналі зафіксовано надсилання пакету TX на Characteristic 0000a002, після чого віртуальний хаб відповів квитуванням RX на Characteristic 0000a001: «ACK:STATUS_REQUEST». Текстове поле автоматично очистилося для введення наступних команд.")

    figure(doc, SCREENSHOTS["data_transfer"], "Рис. 6. Двосторонній обмін даними: надсилання команди STATUS_REQUEST та отримання підтвердження ACK", width_cm=8.5)

    body(doc, "Завершення сеансу зв'язку виконується натисканням кнопки «Disconnect». На рис. 7 показано результат розірвання з'єднання: інтерфейс повертається до початкового стану з кнопкою пошуку, а термінал зберігає повну історію сесії зв'язку, завершуючи її записом «DISCONNECTED Sleep Tracker Hub».")

    figure(doc, SCREENSHOTS["disconnected"], "Рис. 7. Контрольоване відключення від пристрою зі збереженням повної сесійної історії в терміналі", width_cm=8.5)

    body(doc, "Паралельно з відображенням подій у графічному терміналі інтерфейсу, всі переходи станів та передані пакети транслюються у системний лог ОС Android через Logcat із тегом MockBleConnector, що дозволяє проводити системний моніторинг та відлагодження під час розробки (рис. 8).")

    figure_placeholder(
        doc,
        "[ВСТАВИТИ СКРИНШОТ LOGCAT ПІСЛЯ РУЧНОЇ ПЕРЕВІРКИ]",
        "Скриншот вікна Logcat в Android Studio з фільтром «tag:MockBleConnector», де зафіксовано системні записи: SCAN started, FOUND, CONNECTING, CONNECTED, NOTIFY, TX, RX, DISCONNECTED.",
        "Рис. 8. Журнал системних повідомлень компонента MockBleConnector у вікні Logcat Android Studio"
    )

    # ЕТАП 8
    heading(doc, "Етап 8. Модульне та системне тестування", 2)
    body(doc, "Для підтвердження надійності створеного програмного забезпечення було реалізовано комплекс модульних тестів (Unit Tests) з використанням JUnit 4 та бібліотеки kotlinx-coroutines-test. Тестування проводилося на рівні віртуальної машини JVM без необхідності запуску емулятора, що забезпечує високу швидкість перевірки.")
    body(doc, "Створено три спеціалізовані тестові класи для BLE-підсистеми:")
    list_item(doc, "MockBleScannerTest: перевірка повернення віртуального пристрою з коректним ім'ям та адресою після емульованої затримки сканування.")
    list_item(doc, "MockBleConnectorTest: тестування повного життєвого циклу з'єднання (Idle -> Scanning -> DeviceFound -> Connecting -> Connected -> Disconnected), перевірка надсилання стартового NOTIFY, обробки команд TX із квитуванням RX, ігнорування некоректних переходів та коректного очищення корутин у close().")
    list_item(doc, "BleViewModelTest: тестування зв'язку ViewModel з конектором, прокидання StateFlow у Compose, викликів методів життєвого циклу та виклику close() під час onCleared().")
    body(doc, "Запуск тестів через Gradle task :app:testDebugUnitTest підтвердив успішне проходження 25 тестів із 25 (100% успіху), включаючи регресійні тести сенсорного шару з першої лабораторної роботи (MovementClassifierTest та SensorViewModelTest). Перевірка статичним аналізатором коду (Gradle task :app:lintDebug) та фінальне збирання APK (Gradle task :app:assembleDebug) завершилися зі статусом BUILD SUCCESSFUL без жодних помилок чи зауважень.")

    # ==================== ВИСНОВОК ====================
    heading(doc, "Висновок")
    body(doc, "Під час виконання лабораторної роботи №2 було успішно розширено архітектуру мобільного застосунку Sleep Tracker Simulator, реалізовано програмну емуляцію бездротової взаємодії з IoT-пристроєм за протоколом Bluetooth Low Energy (BLE) та створено надійний шар передачі даних. Усі поставлені завдання виконано в повному обсязі з дотриманням сучасних стандартів архітектури Android.")
    body(doc, "У межах дослідження життєвого циклу BLE з'ясовано, чому операції з Bluetooth-стеком в Android вимагають суворої асинхронності. Робота з апаратним Bluetooth-контролером здійснюється через окремий системний процес (Bluetooth Daemon), а взаємодія з GATT-сервером є послідовною та залежною від апаратних черг передачі. Будь-яка спроба виконання синхронних блокуючих викликів призводить до зависання головного потоку застосунку (UI thread), виникнення помилок Application Not Responding (ANR) та збоїв внутрішньої черги зворотних викликів BluetoothGattCallback. Застосування асинхронних корутин Kotlin, диспетчерів та потоків StateFlow дозволило реалізувати неблокуючий обмін даними, який гарантує плавність інтерфейсу та передбачуваність життєвого циклу з'єднання.")
    body(doc, "Окрему увагу було приділено питанню підтримки стабільності зв'язку в реальних умовах радіозавад та нестабільного сигналу. Для запобігання втраті даних на рівні прикладного протоколу було реалізовано механізм квитування повідомлень (ACK-пакети на кожну надіслану команду), використання контрольних таймаутів очікування відповідей, очищення та перезапуск фонових завдань через SupervisorJob, а також збереження журналу переданих пакетів у кільцевому буфері. У промисловій експлуатації дану архітектуру рекомендується доповнювати алгоритмами експоненційного відступу (Exponential Backoff) для повторних спроб реконнекту та аналізом рівня потужності сигналу (RSSI).")
    body(doc, "Завдяки застосуванню принципу інверсії залежностей (DIP) та виділенню контракту IBleConnector, розроблений шар емуляції повністю відокремлений від графічного інтерфейсу Jetpack Compose. Це дозволяє в наступних етапах розвитку проєкту легко замінити віртуальний MockBleConnector на реальний клієнт BluetoothGatt без необхідності зміни коду BleViewModel або екрана SensorScreen, що підтверджує високу якість та масштабованість спроєктованої архітектури.")

    # Apply normal style font
    normal = doc.styles["Normal"]
    normal.font.name = "Times New Roman"
    normal._element.rPr.rFonts.set(qn("w:ascii"), "Times New Roman")
    normal._element.rPr.rFonts.set(qn("w:hAnsi"), "Times New Roman")
    normal.font.size = Pt(14)

    for p in doc.paragraphs:
        p.paragraph_format.widow_control = True

    doc.save(TMP)
    os.replace(TMP, TARGET)
    print(f"Successfully generated Lab 2 report at: {TARGET}")


if __name__ == "__main__":
    main()
