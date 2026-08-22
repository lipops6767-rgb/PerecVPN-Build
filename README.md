# ПЕРЕЦ VPN 1.2 — VLESS / sing-box

Эта версия уже содержит интеграцию реального VPN-движка через sing-box/libbox и Android VpnService. Архитектура: Telegram → ссылка подписки → импорт VLESS → генерация sing-box JSON → системное разрешение VPN → TUN → VLESS.

## Что изменилось
- добавлен `net.clever-vpn:libbox-android:2.1.2` как prebuilt libbox AAR;
- добавлен Android `VpnService`;
- кнопка подключения запрашивает системное VPN-разрешение;
- выбранный VLESS URI превращается в sing-box-конфигурацию;
- поддержаны базовые параметры TLS, Reality, WebSocket, gRPC, HTTP/H2 и HTTPUpgrade;
- TUN создаётся через Android VpnService, а трафик обрабатывает libbox;
- кнопка переключается между подключением и отключением.

## Сборка
Нужен Android Studio с Android SDK и доступом к Maven Central. Gradle скачает libbox автоматически. Для финального APK нужно собрать `assembleDebug` или `assembleRelease`.

Важно: я не вшивал твою личную подписку `perecsub.com/...` в приложение. Пользователь вставляет свою ссылку из Telegram.

## Ограничение
В этой среде нет установленного Android SDK/Gradle wrapper, поэтому я не могу честно назвать ZIP готовым APK и не выдаю неподтверждённую сборку за рабочую. Исходники интеграции подготовлены; после сборки на Android Studio нужно проверить конкретную подписку и Reality/transport параметры.

## Источники архитектуры
sing-box/libbox официально использует Android VpnService/TUN для Android-клиентов.
