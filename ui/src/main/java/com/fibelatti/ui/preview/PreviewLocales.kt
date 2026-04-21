package com.fibelatti.ui.preview

import androidx.compose.ui.tooling.preview.Preview

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Preview(
    name = "Locale Preview - PT",
    group = "Localization",
    showBackground = true,
    locale = "pt",
)
@Preview(
    name = "Locale Preview - DE",
    group = "Localization",
    showBackground = true,
    locale = "de",
)
@Preview(
    name = "Locale Preview - FR",
    group = "Localization",
    showBackground = true,
    locale = "fr",
)
@Preview(
    name = "Locale Preview - RU",
    group = "Localization",
    showBackground = true,
    locale = "ru",
)
@Preview(
    name = "Locale Preview - IW",
    group = "Localization",
    showBackground = true,
    locale = "iw",
)
annotation class PreviewLocales
