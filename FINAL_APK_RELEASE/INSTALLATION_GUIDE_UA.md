# Встановлення SleepSense 2.0 і VirtualSleepBand

## Вимоги

- Телефон №1 для SleepSense: Android 12 / API 31 або новіший, акселерометр і Bluetooth LE.
- Телефон №2 для VirtualSleepBand: Android 8 / API 26 або новіший з підтримкою Bluetooth LE peripheral mode та BLE advertising.
- Не всі смартфони підтримують BLE peripheral mode. Якщо advertising не запускається, це може бути апаратним обмеженням телефону.
- APK підписані локальним Android debug-сертифікатом. Для встановлення потрібно дозволити інсталяцію з вибраного файлового менеджера або браузера.

## Телефон №1 — SleepSense

1. Перенесіть `SleepSense-2.0.apk` на телефон №1.
2. Відкрийте файл і підтвердьте встановлення.
3. Запустіть SleepSense.
4. Надайте дозволи на сповіщення, фізичну активність і Bluetooth nearby devices.
5. Для запису розмістіть телефон на матраці біля подушки. Розміщення на тумбочці не вимірює рухи тіла через матрац.

## Телефон №2 — VirtualSleepBand

1. Перенесіть `VirtualSleepBand.apk` на телефон №2.
2. Встановіть і запустіть застосунок.
3. Надайте дозволи Bluetooth, nearby devices і сповіщення.
4. Натисніть `START BLE ADVERTISING`.
5. Переконайтеся, що статус змінився на `ADVERTISING` і BPM змінюється.

## Підключення

1. На телефоні №1 відкрийте SleepSense → Devices.
2. Виберіть `Physical BLE`.
3. Натисніть `Scan for BLE Devices`.
4. Виберіть VirtualSleepBand і дочекайтеся підключення.
5. Перевірте, що в SleepSense надходить BPM.
6. Поверніться на Today і запустіть запис.
7. Після завершення перевірте History і деталі сесії.

VirtualSleepBand генерує синтетичний BPM. Ці значення не є медичними вимірюваннями.
