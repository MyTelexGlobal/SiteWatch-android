# Инструменты сборки

Для версии 1.5 применяется Gradle 9.8.2 с исправлением sitewatch.1 и Android Gradle Plugin 9.5.0. Официальный загрузчик Wrapper не изменен. Архив Gradle в этом проекте является измененным комплектом и публикуется отдельно в выпуске GitHub.

В официальном Gradle 9.8.2 отсутствует Commons Lang 2.x, остававшийся в Gradle 8.14.6. Однако официальный архив 9.8.2 содержит Guava 33.4.6-jre. Поэтому сценарий tools/prepare_gradle.py заменяет этот JAR официальной Guava 33.7.2-jre и обновляет два описания пути загрузки классов:

1. lib/guava.properties, включая заявленную версию библиотеки.
2. META-INF/MANIFEST.MF внутри lib/gradle-gradle-cli-main-9.8.2.jar.

В измененных файлах есть уведомления о замене. Корневой SITEWATCH-SECURITY-PATCH.json содержит происхождение и суммы исходных файлов. Все остальные файлы исходного дистрибутива сверяются побайтово. Внутри измененного JAR все записи, кроме манифеста, также сверяются побайтово. Временный фильтр классов, применявшийся в версии 1.4, удален: уязвимая библиотека заменена непосредственно.

Сценарий использует только стандартную библиотеку Python, проверяет SHA-256 обоих исходных файлов и запрещает перенаправление загрузки на HTTP. В архиве применяется ZIP_STORED, чтобы результат не зависел от версии zlib. Зафиксированный результат можно получить повторно без доверия к опубликованному измененному архиву:

```sh
python3 tools/prepare_gradle.py --output .toolchain-cache/gradle-9.8.2-sitewatch.1-bin.zip
```

Сравните выведенную сумму с tools/toolchain.json. Параметр --expected-sha256 останавливает сценарий при несовпадении результата. Wrapper проверяет ту же сумму при обычной загрузке.

Зависимости Android Gradle Plugin и Lint получают исправленные версии через gradle/security-versions.properties. Kotlin Gradle Plugin обновлен до стабильной версии 2.4.20: Android Gradle Plugin требует его классы даже при отключенной компиляции Kotlin в этом Java-проекте. Контрольные суммы JAR, AAR и метаданных записаны в gradle/verification-metadata.xml. Не отключайте проверку и не перезаписывайте этот файл без проверки причин изменения зависимостей.

Сценарий tools/security_audit.py учитывает бинарные зависимости проекта и все внешние JAR внутри Gradle. Для последних используются как Maven-описания из JAR, так и описания модулей самого Gradle. Неопознанный JAR, неполный ответ OSV или совпадение с известной уязвимостью приводят к ненулевому коду завершения. В OSV отправляются только публичные названия и версии пакетов.

```sh
python3 tools/security_audit.py --gradle-zip .toolchain-cache/gradle-9.8.2-sitewatch.1-bin.zip --output test-results/security.json
```

Контрольные суммы ограничивают последующую подмену относительно проверенного набора. Они не доказывают безопасность первоначального содержимого всех библиотек. Поиск известных уязвимостей по каталогу не заменяет анализ достижимости кода и не охватывает неизвестные проблемы, саму операционную систему или все компоненты Java и Android SDK.

## Источники

- [Официальные суммы Gradle](https://gradle.org/release-checksums/).
- [Gradle 9.8.2](https://docs.gradle.org/9.8.2/release-notes.html).
- [Требования Android Gradle Plugin 9.5.0](https://developer.android.com/build/releases/agp-9-5-0-release-notes?hl=en).
- [Уведомление Guava](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94).
- [Kotlin 2.4.20](https://github.com/JetBrains/kotlin/releases/tag/v2.4.20).
- [Метод проверки OSV](https://google.github.io/osv.dev/post-v1-querybatch/).
