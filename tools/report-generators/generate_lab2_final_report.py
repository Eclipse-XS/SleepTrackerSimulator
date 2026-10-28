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
TARGET = Path(r"C:\Users\Eclipse\Desktop\7 семестр\Програмування мобільних смарт-систем\ЛР-2_МПСС_Гончаров_Вадим_ОІ-43_final.docx")
TMP = TARGET.with_name(TARGET.stem + ".tmp.docx")

DIAGRAM_PATH = Path(__file__).resolve().parent / "ble_state_machine.png"
SCREENSHOTS = {
    "studio": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 130907.png"),
    "initial": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131034.png"),
    "device_found": Path(r"C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator\tools\device_found_cropped.png"),
    "connected": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131057.png"),
    "data_transfer": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131155.png"),
    "disconnected": Path(r"C:\Users\Eclipse\Pictures\Screenshots\Screenshot 2026-09-29 131315.png"),
    "logcat": Path(r"C:\Users\Eclipse\AndroidStudioProjects\SleepTrackerSimulator\tools\logcat_screen.png"),
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
    p8 = doc.paragraphs[8]
    if len(p8.runs) > 1:
        p8.runs[1].text = "2"
    else:
        p8.text = " до лабораторної роботи № 2"
        set_font(p8.runs[0], size=14, bold=True)

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
    body(doc, "Опанувати принципи роботи з Bluetooth-стеком Android, вивчити життєвий цикл з’єднання та реалізувати механізм сканування й обміну даними (Read, Write, Notify) через програмну абстракцію (Mock-сервіс).")

    # ==================== КОНЦЕПЦІЯ НААСКРІЗНОГО ЗАВДАННЯ ====================
    heading(doc, "Концепція наскрізного завдання")
    body(doc, "У межах серії лабораторних робіт розробляється навчальний застосунок за варіантом №4, Sleep Tracker Simulator. У першій лабораторній роботі було створено базовий сенсорний рівень: отримання даних акселерометра, фільтрацію шуму та розрізнення станів STILL і MOVEMENT.")
    body(doc, "У другій лабораторній роботі застосунок розширюється для взаємодії з умовним зовнішнім смарт-хабом (Sleep Tracker Hub). Оскільки стандартний Android Emulator має обмежені можливості безпосередньої роботи з Bluetooth-адаптером, взаємодію реалізовано через програмну Mock-емуляцію. Вона відтворює послідовність етапів BLE: асинхронний пошук пристрою, затримку підключення, відправку команд та отримання сповіщень.")
    body(doc, "При цьому сенсорний блок із першої лабораторної роботи залишається повністю працездатним: зчитування прискорення та відображення рухової активності продовжують працювати паралельно з новим модулем зв'язку.")

    # ==================== ЗАВДАННЯ НА ЛАБОРАТОРНУ РОБОТУ ====================
    heading(doc, "Завдання на лабораторну роботу")
    list_item(doc, "Оголосити необхідні дозволи (Permissions) для Bluetooth у файлі AndroidManifest.xml з урахуванням версій Android 12+ (API 31+) та зворотної сумісності для старіших версій.", 1)
    list_item(doc, "Спроєктувати GATT-профіль віртуального IoT-пристрою (Sleep Tracker Hub): Service UUID, Characteristic UUID для зчитування статусу і сповіщень (Read/Notify) та Characteristic UUID для запису команд (Write).", 2)
    list_item(doc, "Розробити абстракцію (інтерфейс IBleConnector та клас MockBleScanner), що імітує поведінку BLE: асинхронний пошук пристрою із затримкою та виявлення сервісів.", 3)
    list_item(doc, "Реалізувати скінченний автомат станів з'єднання: переходи між станами Idle, Scanning, DeviceFound, Connecting, Connected, Disconnected та Error.", 4)
    list_item(doc, "Реалізувати передачу даних: надсилання текстових команд через Write Characteristic та емуляцію прийому відповідей (ACK) і сповіщень (Notify).", 5)
    list_item(doc, "Оновити інтерфейс користувача на Jetpack Compose: додати блок керування BLE (статус, кнопки пошуку та підключення, поле вводу команд і термінал виводу логів).", 6)
    list_item(doc, "Забезпечити збереження та спільну роботу функціональності Л.Р. №1 (сенсорний моніторинг руху) без виникнення регресій.", 7)
    list_item(doc, "Покрити розроблену логіку набором модульних тестів (перевірка переходів станів, сканування та обробки команд).", 8)

    # ==================== ТЕХНІЧНИЙ СТЕК ====================
    heading(doc, "Технічний стек")
    list_item(doc, "Мова програмування: Kotlin 2.3.21.")
    list_item(doc, "Платформа: Android (minSdk 31, compileSdk 37, targetSdk 37).")
    list_item(doc, "Інтерфейс користувача: Jetpack Compose (Material 3).")
    list_item(doc, "Архітектурний підхід: MVVM (Model-View-ViewModel), StateFlow, ViewModel.")
    list_item(doc, "Асинхронна робота: Kotlin Coroutines, StateFlow, SupervisorJob, Dispatchers.Main.immediate.")
    list_item(doc, "Модель зв'язку: емуляція Bluetooth Low Energy (GATT-профіль, 128-бітні UUID, семантика Read/Write/Notify).")
    list_item(doc, "Середовище розробки: Android Studio, Gradle 9.6.0.")
    list_item(doc, "Середовище тестування: Android Emulator, Pixel 10 Pro (API 36.1, Android 16).")
    list_item(doc, "Інструменти тестування: JUnit 4, Kotlinx Coroutines Test.")

    # ==================== ОПИС ПРОТОКОЛУ ВЗАЄМОДІЇ ====================
    heading(doc, "Опис протоколу взаємодії")
    body(doc, "Для взаємодії з віртуальним пристроєм змодельовано структуру GATT (Generic Attribute Profile). Вона складається з головного сервісу та двох характеристик, кожна з яких має власний 128-бітний UUID. Параметри спроєктованого профілю наведено в табл. 1.")

    gatt_headers = ["Елемент GATT", "Призначення", "UUID / Ідентифікатор", "Властивості"]
    gatt_rows = [
        ["Service", "Головний сервіс обміну даними Sleep Tracker Hub", "0000a000-0000-1000-8000-00805f9b34fb", "Primary Service"],
        ["Characteristic (Status)", "Зчитування статусу, сповіщення стану та прийом відповідей (ACK)", "0000a001-0000-1000-8000-00805f9b34fb", "Read, Notify"],
        ["Characteristic (Command)", "Передавання текстових команд керування на хаб", "0000a002-0000-1000-8000-00805f9b34fb", "Write"],
        ["Device Info", "Ідентифікатор віртуального пристрою в емуляторі", "Sleep Tracker Hub\n(MOCK:00:11:22:33:44)", "Advertising Data"],
    ]
    gatt_widths = [3.2, 5.0, 5.2, 2.8]
    gatt_table(doc, "Таблиця 1. Специфікація GATT-профілю віртуального хаба Sleep Tracker Hub", gatt_headers, gatt_rows, gatt_widths)

    body(doc, "Спроєктований протокол передбачає наступну логіку обміну повідомленнями:")
    list_item(doc, "Після встановлення з'єднання хаб автоматично надсилає сповіщення (Notify) через характеристику Status: «STATUS:READY». Це сигналізує про готовність приймати команди.")
    list_item(doc, "Користувач відправляє текстову команду шляхом запису рядка (Write) у характеристику Command (наприклад, команду «STATUS_REQUEST»). Mock-конектор приймає непорожній текстовий рядок, видаляє зайві пробіли та фіксує його як вихідний пакет.")
    list_item(doc, "У відповідь на прийняту команду хаб повертає підтвердження через характеристику Status у форматі «ACK:<команда>» (наприклад, «ACK:STATUS_REQUEST»).")

    # ==================== ДІАГРАМА СТАНІВ ПІДКЛЮЧЕННЯ ====================
    heading(doc, "Діаграма станів підключення")
    body(doc, "Життєвий цикл підключення у застосунку організовано у вигляді скінченного автомата. Стани описано через закритий інтерфейс BleConnectionState у Kotlin. Схему переходів наведено на рис. 1.")

    figure(doc, DIAGRAM_PATH, "Рис. 1. Діаграма станів скінченного автомата BLE-з'єднання (MockBleConnector)", width_cm=15.5)

    body(doc, "Автомат реалізує наступну логіку переходів:")
    list_item(doc, "Idle / Disconnected: початковий неактивний стан після запуску або після розірвання зв'язку.")
    list_item(doc, "Scanning: запускається викликом startScan(). У MockBleScanner виконується затримка 2000 мс для імітації часу пошуку в ефірі.")
    list_item(doc, "DeviceFound: сканер повертає знайдений пристрій «Sleep Tracker Hub» (адреса MOCK:00:11:22:33:44), і він з'являється у списку на екрані.")
    list_item(doc, "Connecting: ініціюється після натискання кнопки Connect. Цей стан відтворює часову затримку встановлення BLE-з'єднання тривалістю 750 мс.")
    list_item(doc, "Connected: активний зв'язок. Хаб відправляє початкове сповіщення STATUS:READY, стають доступними поле введення команд і кнопки Send та Disconnect.")
    list_item(doc, "Disconnected: перехід виконується після натискання кнопки Disconnect або закриття екрана. Усі активні фонові завдання скасовуються, а журнал повідомлень зберігається в пам'яті.")
    list_item(doc, "Error: стан помилки, передбачений у структурі BleConnectionState на випадок збою під час сканування.")

    # ==================== ХІД РОБОТИ ====================
    heading(doc, "Хід роботи")
    body(doc, "На початковому етапі було налаштовано необхідні дозволи та визначено профіль віртуального BLE-пристрою. Далі послідовно створено інтерфейс конектора, реалізовано логіку станів з'єднання, додано взаємодію з ViewModel та інтегровано панель керування у Compose-інтерфейс. На завершальних кроках перевірено обмін даними, збереження історії логів та виконано модульне тестування.")

    # ЕТАП 1
    heading(doc, "Етап 1. Оголошення системних дозволів та опис моделей", 2)
    body(doc, "Для роботи з Bluetooth в Android використовуються системні дозволи, оголошені у файлі AndroidManifest.xml. Починаючи з Android 12 (API 31), для сканування та з'єднання потрібні BLUETOOTH_SCAN і BLUETOOTH_CONNECT. Для дозволу BLUETOOTH_SCAN вказано прапорець android:usesPermissionFlags=\"neverForLocation\", оскільки застосунок не використовує Bluetooth для визначення геопозиції.")
    body(doc, "Для зворотної сумісності з версіями до API 31 збережено дозволи BLUETOOTH, BLUETOOTH_ADMIN та ACCESS_FINE_LOCATION з атрибутом android:maxSdkVersion=\"30\". Оскільки в межах лабораторної роботи застосовується Mock-емуляція без звернення до фізичного радіомодуля, запит дозволів під час виконання (runtime permissions) не ініціюється, проте всі обов'язкові декларації в маніфесті присутні.")
    body(doc, "У файлі BleModels.kt створено клас BleDevice(name, address), константи UUID у об'єкті BleProfile та стани з'єднання в закритому інтерфейсі BleConnectionState.")

    # ЕТАП 2
    heading(doc, "Етап 2. Розробка інтерфейсу зв'язку (IBleConnector)", 2)
    body(doc, "Щоб відокремити інтерфейс користувача від конкретної реалізації обміну даними, було створено контракт IBleConnector. Окрім базових функцій connect, disconnect та sendData, його було доповнено властивостями state і logs типу StateFlow, а також методами startScan та close, необхідними для реактивного керування у Compose та життєвого циклу ViewModel. Код інтерфейсу наведено в лістингу 1.")

    code_listing(doc, "Лістинг 1. Інтерфейс зв'язку з BLE-пристроєм (IBleConnector.kt)", r'''
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
    body(doc, "Для емуляції пошуку периферійних вузлів створено клас MockBleScanner. У ньому функція scan() є suspend-функцією, яка через delay(discoveryDelayMillis) імітує затримку сканування (за замовчуванням 2000 мс) і повертає список із віртуальним пристроєм Sleep Tracker Hub (адреса MOCK:00:11:22:33:44). Вихідний код сканера наведено в лістингу 2.")

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
    heading(doc, "Етап 4. Реалізація віртуального конектора (MockBleConnector)", 2)
    body(doc, "Клас MockBleConnector реалізує інтерфейс IBleConnector і керує станом з'єднання через MutableStateFlow. Він містить буфер останніх 50 повідомлень і транслює події у системний лог Android через Logcat із тегом MockBleConnector.")
    body(doc, "Для асинхронної роботи використовується CoroutineScope із SupervisorJob() та Dispatchers.Main.immediate. Під час виклику connect(device) запускається затримка 750 мс, після якої встановлюється стан Connected та надсилається сповіщення NOTIFY STATUS:READY. При виклику sendData(data) додається запис TX про надіслану команду та автоматично формується відповідь RX із квитуванням ACK. Реалізацію основних методів наведено в лістингу 3.")

    code_listing(doc, "Лістинг 3. Реалізація віртуального конектора (MockBleConnector.kt)", r'''
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
    heading(doc, "Етап 5. Керування станом у BleViewModel", 2)
    body(doc, "Для зв'язування логіки конектора з інтерфейсом Compose створено клас BleViewModel у пакеті ui/dashboard. Він отримує IBleConnector через фабричний метод BleViewModel.factory(connector).")
    body(doc, "ViewModel надає незмінні потоки state та logs. Дії користувача (пошук, підключення, відключення, передача команд) передаються відповідним методам конектора. При завершенні життєвого циклу у методі onCleared() викликається connector.close(), що скасовує незавершені корутини. Код BleViewModel наведено в лістингу 4.")

    code_listing(doc, "Лістинг 4. Керування станом Bluetooth у ViewModel (BleViewModel.kt)", r'''
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
    heading(doc, "Етап 6. Розширення інтерфейсу користувача на Jetpack Compose", 2)
    body(doc, "Інтерфейс користувача оновлено у файлі SensorScreen.kt. Блок керування Mock BLE розміщено під сенсорною панеллю, а весь екран обгорнуто у вертикальний скрол через verticalScroll(rememberScrollState()). Завдяки цьому забезпечено паралельну роботу сенсора та модуля зв'язку.")
    body(doc, "На рис. 2 показано загальний вигляд вікна Android Studio та виконання застосунку на емуляторі Pixel 10 Pro. У верхній частині відображаються показники акселерометра з Л.Р. №1 (стан STILL, прискорення 9,81 м/с²), а нижче розташовано новий блок Mock BLE connection.")

    figure(doc, SCREENSHOTS["studio"], "Рис. 2. Робоче середовище Android Studio та виконання застосунку Sleep Tracker Simulator на емуляторі Pixel 10 Pro", width_cm=15.5)

    body(doc, "На рис. 3 показано початковий стан блоку зв'язку: статус Disconnected, активна кнопка «Search for devices» та порожній термінал.")

    figure(doc, SCREENSHOTS["initial"], "Рис. 3. Початковий стан панелі Mock BLE (Disconnected) з можливістю запуску пошуку пристроїв", width_cm=7.2)

    body(doc, "Після натискання «Search for devices» сканер переходить у стан Scanning, а після затримки 2000 мс - у стан DeviceFound. На екрані з'являється розділ «Discovered devices» із карткою знайденого хаба Sleep Tracker Hub (MOCK:00:11:22:33:44) та кнопкою Connect (рис. 4).")

    figure(doc, SCREENSHOTS["device_found"], "Рис. 4. Відображення знайденого периферійного пристрою Sleep Tracker Hub у стані Device found", width_cm=7.2)

    # ЕТАП 7
    heading(doc, "Етап 7. Емуляція обміну даними та термінал подій", 2)
    body(doc, "Після натискання «Connect» система переходить у стан Connecting, а після закінчення затримки 750 мс - у стан Connected. Статус змінюється на «Connected: Sleep Tracker Hub», індикатор стає зеленим, відкривається поле вводу команди та кнопка Disconnect.")
    body(doc, "У терміналі з'являються записи про запуск сканування, знаходження хаба, підключення та прийом початкового сповіщення: NOTIFY 0000a001-0000-1000-8000-00805f9b34fb: STATUS:READY (рис. 5).")

    figure(doc, SCREENSHOTS["connected"], "Рис. 5. Встановлення з'єднання з віртуальним хабом та отримання сповіщення NOTIFY: STATUS:READY", width_cm=8.5)

    body(doc, "Для тестування передачі даних у текстове поле введено команду «STATUS_REQUEST» та натиснуто «Send». У терміналі зафіксовано надсилання пакету TX на Characteristic 0000a002 та отримання квитування RX на Characteristic 0000a001: «ACK:STATUS_REQUEST» (рис. 6).")

    figure(doc, SCREENSHOTS["data_transfer"], "Рис. 6. Двосторонній обмін даними: надсилання команди STATUS_REQUEST та отримання підтвердження ACK", width_cm=8.5)

    body(doc, "При натисканні «Disconnect» з'єднання розривається, статус повертається до Disconnected, а термінал зберігає повну історію сесії зв'язку (рис. 7).")

    figure(doc, SCREENSHOTS["disconnected"], "Рис. 7. Відключення від пристрою зі збереженням повної історії сесії в терміналі", width_cm=8.5)

    body(doc, "Усі події з'єднання та обміну пакетами паралельно фіксуються в системному вікні Logcat Android Studio з тегом MockBleConnector (рис. 8). На скриншоті чітко видно послідовність записів: SCAN started, FOUND, CONNECTING, CONNECTED, NOTIFY, відправку TX STATUS_REQUEST, прийом RX ACK:STATUS_REQUEST та завершення DISCONNECTED.")

    figure(doc, SCREENSHOTS["logcat"], "Рис. 8. Журнал системних повідомлень компонента MockBleConnector у вікні Logcat Android Studio", width_cm=15.5)

    # ЕТАП 8
    heading(doc, "Етап 8. Модульне та системне тестування", 2)
    body(doc, "Для перевірки коректності створеного коду розроблено набір модульних тестів (Unit Tests) з використанням JUnit 4 та kotlinx-coroutines-test:")
    list_item(doc, "MockBleScannerTest (2 тести): перевірка затримки пошуку та повернення коректного віртуального пристрою.")
    list_item(doc, "MockBleConnectorTest (13 тестів): перевірка повного циклу станів, надсилання стартового NOTIFY, генерації ACK-відповідей, ігнорування некоректних переходів та коректного завершення роботи корутин.")
    list_item(doc, "BleViewModelTest (4 тести): перевірка передачі стану через StateFlow, викликів методів конектора та виклику close() у методі onCleared().")
    list_item(doc, "MovementClassifierTest (4 тести), SensorSampleTest (1 тест), ExampleUnitTest (1 тест): збережені тести з першої лабораторної роботи для контролю відсутності регресій.")
    body(doc, "Автоматизована перевірка через команду .\\gradlew.bat test підтвердила успішне проходження всіх 25 тестів із 25. Команди .\\gradlew.bat assembleDebug та .\\gradlew.bat lint також завершилися зі статусом BUILD SUCCESSFUL без помилок.")

    # ==================== ВИСНОВОК ====================
    heading(doc, "Висновок")
    body(doc, "У ході виконання лабораторної роботи №2 було реалізовано програмну Mock-емуляцію взаємодії з IoT-пристроєм за протоколом Bluetooth Low Energy (BLE) у застосунку Sleep Tracker Simulator. Створено GATT-профіль з унікальними UUID, реалізовано кінцевий автомат з'єднання, обмін даними (Write та Notify з квитуванням ACK) та журнал подій у терміналі й Logcat. Повністю збережено функціональність сенсорного моніторингу з першої лабораторної роботи.")
    body(doc, "Операції з Bluetooth-стеком в Android вимагають обов'язкової асинхронності, оскільки сканування ефіру, встановлення GATT-з'єднання та передача характеристик мають непередбачувану тривалість. Виконання цих викликів у синхронному режимі призвело б до блокування головного потоку застосунку (UI thread) та появи помилок Application Not Responding (ANR). Використання корутин Kotlin та потоків StateFlow забезпечує неблокуючу роботу інтерфейсу та коректне керування життєвим циклом.")
    body(doc, "У реальних умовах експлуатації стабільність бездротового зв'язку BLE при перешкодах забезпечується комплексом заходів: використанням прикладного квитування (ACK), налаштуванням таймаутів очікування відповідей, механізмами повторних спроб (retries) з експоненційним відступом (Exponential Backoff), моніторингом рівня потужності сигналу (RSSI), буферизацією пакетів та обробкою помилок з'єднання. У створеній навчальній Mock-реалізації базову стабільність забезпечено детермінованим кінцевим автоматом, захистом від повторного сканування, коректним скасуванням активних корутин при зміні станів та очищенням ресурсів у close().")
    body(doc, "Використання інтерфейсу IBleConnector відокремлює шар зв'язку від графічного інтерфейсу Jetpack Compose, що дозволяє за потреби підключити справжній BluetoothGatt-клієнт без змін у коді екрана.")

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
    print(f"Successfully generated Lab 2 FINAL report at: {TARGET}")


if __name__ == "__main__":
    main()
