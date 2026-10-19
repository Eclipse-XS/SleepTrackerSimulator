# Сценарій демонстрації лабораторної роботи №7

## Підготовка

1. Запустити `Pixel_10_Pro`, Firebase Emulator Suite і debug-збірку.
2. Переконатися, що застосунок показує Dashboard без crash і має доступ до акселерометра.
3. Не демонструвати production-ключі. Firebase працює з тестовим проєктом або емулятором.

## Послідовність

1. На Dashboard показати X/Y/Z, movement intensity, ML widget, статус Firebase, BLE та останні п'ять Room-записів.
2. Натиснути Find Sleep Band, під'єднати VirtualSleepBand, увімкнути HR notifications.
3. Відкрити Smart Scenarios. Створити правило `Accelerometer X > 10.0 m/s²`, action `VIBRATION_PULSE`, три послідовні збіги, cooldown 5000 ms.
4. Закрити і знову запустити застосунок. Показати збережене правило.
5. У Extended Controls > Virtual Sensors змінити X. Підтримати значення вище 10 щонайменше три samples.
6. Показати результат actuator і запис виконання. Утримувати X вище порога та показати відсутність alert spam.
7. Створити або змінити action на `MOCK_BLE_ALERT`. Повторно перетнути поріг після rearm і показати `ALERT_ACKNOWLEDGED` у BLE log.
8. Показати AI Insights. Наголосити, що REST/RESTLESS/ACTIVE походять з реального TFLite `[1,20,3]`, але не є клінічними фазами сну.
9. Запустити monitoring. Вимкнути ефективну мережу, отримати pending outbox, потім відновити мережу і дочекатися acknowledgement.
10. Завершити monitoring та показати припинення нових записів.

## Наявний відеодоказ

`docs/evidence/lab7/lab7-smart-scenario.mp4` є справжнім `adb shell screenrecord`. Він демонструє створення та збереження правила. Він не є доказом повного Sensor → ML → Room → Firebase → Actuator → Mock BLE циклу; повний запис слід виконувати за сценарієм вище з desktop recorder, бо `adb screenrecord` не захоплює Extended Controls і Firebase Console.
